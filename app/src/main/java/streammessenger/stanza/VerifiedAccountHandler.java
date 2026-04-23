package streammessenger.stanza;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.sql.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.mutlidevice.CarbonManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles verified account badges.
 *
 * Custom namespace: urn:xmpp:verified:0
 *
 * WHAT IT IS:
 * ─────────────
 * A blue checkmark (or gold star) next to a user's name indicating
 * their identity has been verified by the server admin.
 *
 * TYPES:
 *   blue_check   → Verified public figure / creator
 *   gold_check   → Verified business / organization
 *   official     → Official server/bot account
 *
 * WHO GRANTS VERIFICATION:
 * ─────────────────────────
 * Only server admins can grant/revoke verification.
 * There is NO self-service verification (unlike Twitter's paid system).
 * This prevents abuse.
 *
 * HOW IT WORKS:
 * ─────────────
 * 1. Admin grants verification via the admin API or this handler
 * 2. Verified status is stored in users.verified table
 * 3. When a message is sent, the server appends the verified badge
 *    to the stanza so recipients can display the checkmark
 * 4. Recipients verify the badge came from the server (not self-reported)
 *
 * STANZA EXAMPLE (server appends this automatically to messages):
 *   <message from='celebrity@domain.com' to='fan@domain.com'>
 *     <body>Hello everyone!</body>
 *     <verified xmlns='urn:xmpp:verified:0'
 *               type='blue_check'
 *               since='2024-01-15T00:00:00Z'/>
 *   </message>
 *
 * OPERATIONS (admin only):
 *   grant   → Grant verification to a user
 *   revoke  → Revoke verification
 *   check   → Check if a user is verified
 */
public final class VerifiedAccountHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(VerifiedAccountHandler.class.getName());

    private static final String VERIFIED_NS = "urn:xmpp:verified:0";

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    public VerifiedAccountHandler(ConnectionPool pool,
                                  SessionRegistry registry) {
        this.pool     = pool;
        this.registry = registry;
    }

    @Override
    public void handle(StartElement element,
                        XMLEventReader reader,
                        Session session) {

        if (!session.isAuthenticated()) {
            sendError(session, null, "not-authorized");
            consumeElement(reader);
            return;
        }

        String iqId = getAttr(element, "id");
        VerifiedRequest req = parseRequest(reader);

        if (req == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (req.action()) {
            case "grant"  -> handleGrant(req, iqId, session);
            case "revoke" -> handleRevoke(req, iqId, session);
            case "check"  -> handleCheck(req, iqId, session);
            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Grant Verification (Admin Only)
    // =========================================================================

    private void handleGrant(VerifiedRequest req,
                              String iqId,
                              Session session) {

        if (!isAdmin(session)) {
            sendError(session, iqId, "forbidden");
            return;
        }

        if (req.targetJid() == null || req.verificationText() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String type = req.verificationType() != null
                ? req.verificationType() : "blue_check";

        if (!isValidType(type)) {
            sendError(session, iqId, "bad-request");
            return;
        }

        boolean granted = grantVerification(
                req.targetJid(), type, req.verificationText(),
                extractUserId(session.getContactId()));

        if (!granted) {
            sendError(session, iqId, "item-not-found");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<verified xmlns='%s' action='granted'>" +
            "<jid>%s</jid>" +
            "<type>%s</type>" +
            "</verified></iq>",
            escapeXml(iqId), VERIFIED_NS,
            escapeXml(req.targetJid()), escapeXml(type)
        ));

        // Notify the verified user
        registry.getByContactId(req.targetJid())
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(String.format(
                    "<message type='headline'>" +
                    "<verified-notification xmlns='%s'>" +
                    "<type>%s</type>" +
                    "<message>Congratulations! Your account is now verified.</message>" +
                    "</verified-notification></message>",
                    VERIFIED_NS, escapeXml(type)
                )));

        logger.info("Verification granted: jid=" + req.targetJid()
                + " type=" + type
                + " by=" + session.getContactId());
    }

    // =========================================================================
    // Revoke Verification (Admin Only)
    // =========================================================================

    private void handleRevoke(VerifiedRequest req,
                               String iqId,
                               Session session) {

        if (!isAdmin(session)) {
            sendError(session, iqId, "forbidden");
            return;
        }

        if (req.targetJid() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        boolean revoked = revokeVerification(req.targetJid());

        if (!revoked) {
            sendError(session, iqId, "item-not-found");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<verified xmlns='%s' action='revoked'>" +
            "<jid>%s</jid>" +
            "</verified></iq>",
            escapeXml(iqId), VERIFIED_NS,
            escapeXml(req.targetJid())
        ));

        logger.info("Verification revoked: jid=" + req.targetJid()
                + " by=" + session.getContactId());
    }

    // =========================================================================
    // Check Verification (Anyone)
    // =========================================================================

    private void handleCheck(VerifiedRequest req,
                              String iqId,
                              Session session) {

        if (req.targetJid() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        VerificationRecord record = getVerification(req.targetJid());

        if (record == null) {
            session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                "<verified xmlns='%s' action='check'>" +
                "<jid>%s</jid>" +
                "<verified>false</verified>" +
                "</verified></iq>",
                escapeXml(iqId), VERIFIED_NS,
                escapeXml(req.targetJid())
            ));
        } else {
            session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                "<verified xmlns='%s' action='check'>" +
                "<jid>%s</jid>" +
                "<verified>true</verified>" +
                "<type>%s</type>" +
                "<text>%s</text>" +
                "<since>%s</since>" +
                "</verified></iq>",
                escapeXml(iqId), VERIFIED_NS,
                escapeXml(req.targetJid()),
                escapeXml(record.type()),
                escapeXml(record.verificationText()),
                record.grantedAt()
            ));
        }
    }

    // =========================================================================
    // Stanza decoration
    // =========================================================================

    /**
     * Called by MessageHandler to append the verified badge to outgoing stanzas.
     * Only appends if the sender is verified.
     *
     * @param senderJid The message sender's bare JID
     * @return XML fragment to append, or empty string if not verified
     */
    public String getVerifiedBadgeXml(String senderJid) {
        VerificationRecord record = getVerification(senderJid);
        if (record == null) return "";

        return String.format(
            "<verified xmlns='%s' type='%s' since='%s'/>",
            VERIFIED_NS,
            escapeXml(record.type()),
            record.grantedAt()
        );
    }

    // =========================================================================
    // Database
    // =========================================================================

    private boolean grantVerification(String jid,
                                       String type,
                                       String verificationText,
                                       String grantedByUserId) {
        String sql = """
            INSERT INTO verified_accounts (
                user_id, verification_type,
                verification_text, granted_by,
                granted_at
            )
            SELECT u.user_id, ?, ?, ?, NOW()
            FROM users u
            WHERE u.jid = ? AND u.deleted_at IS NULL
            ON CONFLICT (user_id) DO UPDATE SET
                verification_type = EXCLUDED.verification_type,
                verification_text = EXCLUDED.verification_text,
                granted_by        = EXCLUDED.granted_by,
                granted_at        = NOW(),
                revoked_at        = NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, type);
            stmt.setString(2, verificationText);
            stmt.setString(3, grantedByUserId);
            stmt.setString(4, jid);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("grantVerification error: " + e.getMessage());
            return false;
        }
    }

    private boolean revokeVerification(String jid) {
        String sql = """
            UPDATE verified_accounts
            SET revoked_at = NOW()
            WHERE user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            AND revoked_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, jid);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("revokeVerification error: " + e.getMessage());
            return false;
        }
    }

    private VerificationRecord getVerification(String jid) {
        String sql = """
            SELECT va.verification_type,
                   va.verification_text,
                   va.granted_at::text
            FROM verified_accounts va
            INNER JOIN users u ON u.user_id = va.user_id
            WHERE u.jid = ?
              AND va.revoked_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, jid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new VerificationRecord(
                        rs.getString("verification_type"),
                        rs.getString("verification_text"),
                        rs.getString("granted_at")
                );
            }

        } catch (SQLException e) {
            logger.warning("getVerification error: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isAdmin(Session session) {
        // Check if user has admin role
        String sql = """
            SELECT 1 FROM user_roles
            WHERE user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            AND role = 'admin'
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, session.getContactId());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isValidType(String type) {
        return "blue_check".equals(type)
                || "gold_check".equals(type)
                || "official".equals(type);
    }

    private VerifiedRequest parseRequest(XMLEventReader reader) {
        String action           = null;
        String targetJid        = null;
        String verificationType = null;
        String verificationText = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    if ("verified".equals(name) && VERIFIED_NS.equals(ns)) {
                        action           = getAttr(se, "action");
                        verificationType = getAttr(se, "type");
                    }

                    switch (name) {
                        case "jid"  -> targetJid        = readText(reader);
                        case "text" -> verificationText = readText(reader);
                    }
                }

                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("parseRequest error: " + e.getMessage());
            return null;
        }

        return new VerifiedRequest(action,targetJid, verificationType, verificationText);
    }

    private String readText(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent e = reader.peek();
                if (e.isCharacters()) {
                    reader.nextEvent();
                    sb.append(e.asCharacters().getData());
                } else break;
            }
            if (reader.hasNext() && reader.peek().isEndElement()) {
                reader.nextEvent();
            }
        } catch (XMLStreamException ignored) {}
        return sb.toString().trim();
    }

    private String getAttr(StartElement el, String name) {
        Attribute attr = el.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private void sendError(Session s, String iqId, String cond) {
        s.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='cancel'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
                cond
        ));
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

    private record VerifiedRequest(
            String action,
            String targetJid,
            String verificationType,
            String verificationText
    ) {}

    private record VerificationRecord(
            String type,
            String verificationText,
            String grantedAt
    ) {}
}