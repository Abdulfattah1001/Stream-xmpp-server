package streammessenger.stanza;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles message reactions (emoji responses).
 *
 * Custom namespace: urn:xmpp:reactions:0
 *
 * WHAT IT DOES:
 * ─────────────
 * User long-presses a message → picks an emoji → sends reaction.
 * All participants in the conversation see the reaction appear.
 *
 * OPERATIONS:
 *   add     → Add a reaction (or replace existing reaction on same message)
 *   remove  → Remove a reaction
 *   fetch   → Get all reactions for a message
 *
 * LIMITS:
 *   - One reaction type per user per message (you can only ❤️ once)
 *   - Max 20 different reaction types per message
 *   - Any Unicode emoji allowed + custom reaction IDs (custom_001)
 *
 * Example - Add reaction:
 *   <message to='alice@domain.com'>
 *     <reactions xmlns='urn:xmpp:reactions:0'
 *                action='add'
 *                message_id='msg-uuid'
 *                reaction='❤️'/>
 *   </message>
 *
 * Example - Server broadcasts to all participants:
 *   <message from='bob@domain.com' to='alice@domain.com'>
 *     <reactions xmlns='urn:xmpp:reactions:0'
 *                action='added'
 *                message_id='msg-uuid'
 *                reaction='❤️'
 *                from_user_id='u_bob123'
 *                total='5'/>
 *   </message>
 */
public final class ReactionHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(ReactionHandler.class.getName());

    private static final String REACTION_NS = "urn:xmpp:reactions:0";

    // Max distinct reaction types per message
    private static final int MAX_REACTION_TYPES = 20;

    // Max emoji codepoints (prevents extremely long reaction strings)
    private static final int MAX_REACTION_LENGTH = 8;

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    public ReactionHandler(ConnectionPool pool, SessionRegistry registry) {
        this.pool     = pool;
        this.registry = registry;
    }

    @Override
    public void handle(StartElement element,
                        XMLEventReader reader,
                        Session session) {

        if (!session.isAuthenticated()) {
            consumeElement(reader);
            return;
        }

        // Reactions come as <message> stanzas, not <iq>
        // The <reactions> child element carries the data
        ReactionRequest req = parseReactionFromMessage(reader);
        if (req == null) {
            consumeElement(reader);
            return;
        }

        switch (req.action()) {
            case "add"    -> handleAddReaction(req, session);
            case "remove" -> handleRemoveReaction(req, session);
            case "fetch"  -> handleFetchReactions(req, session);
            default -> consumeElement(reader);
        }
    }

    // =========================================================================
    // Add Reaction
    // =========================================================================

    private void handleAddReaction(ReactionRequest req, Session session) {
        if (req.messageId() == null || req.reaction() == null) return;
        if (req.reaction().length() > MAX_REACTION_LENGTH) return;

        String userId = extractUserId(session.getContactId());

        // Upsert: one reaction per user per message
        // If user already reacted, replace with new reaction
        AddReactionResult result = upsertReaction(
                req.messageId(), userId, req.reaction());

        if (result == null) return;

        // Broadcast to all conversation participants
        broadcastReaction(req.messageId(), session.getContactId(),
                userId, req.reaction(), "added", result.totalForType());
    }

    // =========================================================================
    // Remove Reaction
    // =========================================================================

    private void handleRemoveReaction(ReactionRequest req, Session session) {
        if (req.messageId() == null || req.reaction() == null) return;

        String userId = extractUserId(session.getContactId());
        int remaining = deleteReaction(
                req.messageId(), userId, req.reaction());

        // Broadcast removal to all participants
        broadcastReaction(req.messageId(), session.getContactId(),
                userId, req.reaction(), "removed", remaining);
    }

    // =========================================================================
    // Fetch All Reactions
    // =========================================================================

    private void handleFetchReactions(ReactionRequest req, Session session) {
        if (req.messageId() == null) return;

        List<ReactionSummary> reactions = getReactionSummary(req.messageId(),
                extractUserId(session.getContactId()));

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<message type='result'>" +
            "<reactions xmlns='%s' action='summary'" +
            " message_id='%s'>",
            REACTION_NS, escapeXml(req.messageId())
        ));

        for (ReactionSummary r : reactions) {
            xml.append(String.format(
                "<reaction emoji='%s' count='%d' user_reacted='%b'/>",
                escapeXml(r.emoji()), r.count(), r.userReacted()
            ));
        }

        xml.append("</reactions></message>");
        session.writeXML(xml.toString());
    }

    // =========================================================================
    // Broadcast
    // =========================================================================

    /**
     * Broadcasts a reaction event to all conversation participants.
     *
     * For DMs: sender and recipient
     * For groups: all group members
     */
    private void broadcastReaction(String messageId,
                                    String fromJid,
                                    String fromUserId,
                                    String reaction,
                                    String action,
                                    int totalForType) {

        List<String> participantJids = getMessageParticipants(messageId);

        String notification = String.format(
            "<message from='%s'>" +
            "<reactions xmlns='%s'" +
            " action='%s'" +
            " message_id='%s'" +
            " reaction='%s'" +
            " from_user_id='%s'" +
            " total='%d'/>" +
            "</message>",
            escapeXml(fromJid),
            REACTION_NS, action,
            escapeXml(messageId),
            escapeXml(reaction),
            fromUserId,
            totalForType
        );

        for (String jid : participantJids) {
            registry.getByContactId(jid)
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(notification));
        }
    }

    // =========================================================================
    // Database operations
    // =========================================================================

    private AddReactionResult upsertReaction(String messageId,
                                              String userId,
                                              String reaction) {
        String sql = """
            INSERT INTO message_reactions (message_id, user_id, reaction, created_at)
            SELECT ?::uuid, ?, ?, NOW()
            WHERE (
                SELECT COUNT(DISTINCT reaction)
                FROM message_reactions
                WHERE message_id = ?::uuid
            ) < ?
            ON CONFLICT (message_id, user_id, reaction) DO NOTHING
            """;

        String countSql = """
            SELECT COUNT(*) AS total
            FROM message_reactions
            WHERE message_id = ?::uuid AND reaction = ?
            """;

        try (Connection conn = pool.getConnection()) {

            // Remove any existing reaction from this user first
            // (one reaction type per user per message)
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM message_reactions " +
                    "WHERE message_id = ?::uuid AND user_id = ?")) {
                del.setString(1, messageId);
                del.setString(2, userId);
                del.executeUpdate();
            }

            // Insert new reaction
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, messageId);
                stmt.setString(2, userId);
                stmt.setString(3, reaction);
                stmt.setString(4, messageId);
                stmt.setInt(5, MAX_REACTION_TYPES);
                stmt.executeUpdate();
            }

            // Get count for this reaction type
            int total = 0;
            try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                stmt.setString(1, messageId);
                stmt.setString(2, reaction);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt("total");
                }
            }

            conn.commit();
            return new AddReactionResult(total);

        } catch (SQLException e) {
            logger.warning("upsertReaction error: " + e.getMessage());
            return null;
        }
    }

    private int deleteReaction(String messageId,
                                String userId,
                                String reaction) {
        String sql = """
            DELETE FROM message_reactions
            WHERE message_id = ?::uuid
              AND user_id    = ?
              AND reaction   = ?
            """;

        String countSql = """
            SELECT COUNT(*) FROM message_reactions
            WHERE message_id = ?::uuid AND reaction = ?
            """;

        try (Connection conn = pool.getConnection()) {

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, messageId);
                stmt.setString(2, userId);
                stmt.setString(3, reaction);
                stmt.executeUpdate();
            }

            int remaining = 0;
            try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                stmt.setString(1, messageId);
                stmt.setString(2, reaction);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) remaining = rs.getInt(1);
                }
            }

            conn.commit();
            return remaining;

        } catch (SQLException e) {
            logger.warning("deleteReaction error: " + e.getMessage());
            return 0;
        }
    }

    private List<ReactionSummary> getReactionSummary(String messageId,
                                                      String viewerUserId) {
        String sql = """
            SELECT
                reaction,
                COUNT(*) AS total,
                EXISTS(
                    SELECT 1 FROM message_reactions mr2
                    WHERE mr2.message_id = mr.message_id
                      AND mr2.reaction   = mr.reaction
                      AND mr2.user_id    = ?
                ) AS user_reacted
            FROM message_reactions mr
            WHERE message_id = ?::uuid
            GROUP BY reaction
            ORDER BY total DESC
            """;

        List<ReactionSummary> result = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, viewerUserId);
            stmt.setString(2, messageId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new ReactionSummary(
                            rs.getString("reaction"),
                            rs.getInt("total"),
                            rs.getBoolean("user_reacted")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.warning("getReactionSummary error: " + e.getMessage());
        }

        return result;
    }

    private List<String> getMessageParticipants(String messageId) {
        String sql = """
            SELECT DISTINCT u.jid
            FROM messages m
            INNER JOIN users u ON
                u.user_id = m.from_user_id OR u.user_id = m.to_user_id
            WHERE m.message_id::text = ?
            """;

        List<String> jids = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) jids.add(rs.getString("jid"));
            }

        } catch (SQLException e) {
            logger.warning("getMessageParticipants error: " + e.getMessage());
        }

        return jids;
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private ReactionRequest parseReactionFromMessage(XMLEventReader reader) {
        String action    = null;
        String messageId = null;
        String reaction  = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    if ("reactions".equals(name) && REACTION_NS.equals(ns)) {
                        action    = getAttr(se, "action");
                        messageId = getAttr(se, "message_id");
                        reaction  = getAttr(se, "reaction");
                    }
                }

                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing reaction: " + e.getMessage());
            return null;
        }

        return new ReactionRequest(action, messageId, reaction);
    }

    private String getAttr(StartElement el, String name) {
        Attribute attr = el.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private void consumeElement(XMLEventReader reader) {
        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent e = reader.nextEvent();
                if (e.isStartElement()) depth++;
                if (e.isEndElement()) depth--;
            }
        } catch (XMLStreamException ignored) {}
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record ReactionRequest(
            String action,
            String messageId,
            String reaction
    ) {}

    private record AddReactionResult(int totalForType) {}

    private record ReactionSummary(
            String emoji,
            int count,
            boolean userReacted
    ) {}
}