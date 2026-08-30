package streammessenger.stanza;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * CRDT-based collaborative notes.
 * <p>
 * PHILOSOPHY:
 * ───────────
 * The server does NOT store note content.
 * The server is a pure message router with buffering.
 * <p>
 * WHAT THE SERVER DOES:
 * ─────────────────────
 * 1. Tracks note metadata (title, participants, timestamps)
 * 2. Routes CRDT operations to online participants
 * 3. Buffers operations for offline participants (30 day TTL)
 * 4. Delivers buffered operations on reconnect
 * <p>
 * WHAT THE SERVER DOES NOT DO:
 * ─────────────────────────────
 * 1. Parse operation content - it's opaque JSON
 * 2. Apply operations to any note state
 * 3. Store note content
 * 4. Resolve conflicts (CRDT handles this on clients)
 * 5. Maintain history (clients hold full history)
 * <p>
 * CUSTOM NAMESPACE: urn:xmpp:crdt-note:0
 * <p>
 * OPERATIONS:
 * ───────────
 *   create-note      → Register a new note with metadata
 *   invite           → Add a participant
 *   remove-participant → Remove a participant
 *   op               → Send a CRDT operation (opaque payload)
 *   fetch-pending    → Get buffered ops on reconnect
 *   delete-note      → Delete note metadata (participants delete locally)
 *   list-notes       → Get notes user is in
 * <p>
 * Example - Create note:
 *   <iq type='set' id='n1'>
 *     <crdt-note xmlns='urn:xmpp:crdt-note:0' action='create'>
 *       <title>Meeting Notes</title>
 *       <conversation_jid>u_bob@domain.com</conversation_jid>
 *       <participants>
 *         <user_id>u_bob123</user_id>
 *       </participants>
 *     </crdt-note>
 *   </iq>
 * <p>
 * Example - Send operation (server treats payload as opaque):
 *   <message id='op1' to='conv-jid' type='chat'>
 *     <crdt-op xmlns='urn:xmpp:crdt-note:0'
 *              note_id='uuid'
 *              clock='42'>
 *       {"type":"insert","pos_id":"3.5.abc","char":"H","after":"3.4.xyz"}
 *     </crdt-op>
 *   </message>
 * <p>
 * Example - Fetch pending ops (after reconnect):
 *   <iq type='get' id='n2'>
 *     <crdt-note xmlns='urn:xmpp:crdt-note:0' action='fetch-pending'/>
 *   </iq>
 */
public final class CRDTNoteHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(CRDTNoteHandler.class.getName());

    private static final String CRDT_NS = "urn:xmpp:crdt-note:0";

    // Max op payload size to prevent abuse
    private static final int MAX_OP_SIZE = 8192;

    // Max buffered ops per recipient per note
    private static final int MAX_BUFFERED_OPS = 10_000;

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    public CRDTNoteHandler(ConnectionPool pool, SessionRegistry registry) {
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

        String iqId  = getAttr(element, "id");
        String type  = getAttr(element, "type");

        NoteRequest req = parseRequest(reader);
        if (req == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (req.action()) {
            case "create"             -> handleCreate(req, iqId, session);
            case "invite"             -> handleInvite(req, iqId, session);
            case "remove-participant" -> handleRemoveParticipant(req, iqId, session);
            case "op"                 -> handleOp(req, session);
            case "fetch-pending"      -> handleFetchPending(iqId, session);
            case "delete"             -> handleDelete(req, iqId, session);
            case "list-notes"         -> handleListNotes(iqId, session);
            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Handle: Direct CRDT op forwarding
    // =========================================================================

    /**
     * Routes a CRDT operation to all participants of a note.
     * <p>
     * SERVER LOGIC:
     * 1. Verify sender is a participant of the note
     * 2. For each OTHER participant:
     *    - If online: forward the op stanza directly
     *    - If offline: buffer the opaque payload
     * 3. Do NOT parse, transform, or store the payload
     * <p>
     * PAYLOAD IS OPAQUE - server treats it as a black box.
     */
    private void handleOp(NoteRequest req, Session senderSession) {
        logger.info("Payload: "+req.opPayload());
        if (req.noteId() == null || req.opPayload() == null) {
            return;
        }

        if (req.opPayload().length() > MAX_OP_SIZE) {
            logger.warning("CRDT op too large from uid="
                    + senderSession.getUid());
            return;
        }

        String senderUserId = extractUserId(senderSession.getContactId());

        // Get all participants except sender
        List<String> otherParticipants = getOtherParticipants(
                req.noteId(), senderUserId);

        if (otherParticipants.isEmpty()) return;

        // Build the outbound op stanza
        // Server doesn't touch the payload - just relays it
        String opStanza = String.format(
            "<message type='crdt-op' from='%s'>" +
            "<crdt-op xmlns='%s' note_id='%s' clock='%d' sender_id='%s'>" +
            "%s" +
            "</crdt-op></message>",
            escapeXml(senderSession.getContactId()),
            CRDT_NS,
            escapeXml(req.noteId()),
            req.lamportClock(),
            escapeXml(senderUserId),
            req.opPayload()  // Opaque - already validated size
        );

        // Route to each participant
        int delivered = 0;
        int buffered  = 0;

        for (String recipientUserId : otherParticipants) {
            String recipientJid = getUserJid(recipientUserId);
            if (recipientJid == null) continue;

            // Try live delivery to any online session
            List<Session> recipientSessions =
                    registry.getSessionsByContactId(recipientJid);

            boolean liveDelivered = false;
            for (Session recipient : recipientSessions) {
                if (recipient.isAuthenticated()
                        && recipient.writeXML(opStanza)) {
                    liveDelivered = true;
                }
            }

            if (liveDelivered) {
                delivered++;
            } else {
                // Buffer for offline delivery
                bufferOp(req.noteId(), recipientUserId,
                        req.opPayload(), senderSession.getContactId(),
                        req.lamportClock());
                buffered++;
            }
        }

        logger.fine("CRDT op routed: noteId=" + req.noteId()
                + " clock=" + req.lamportClock()
                + " delivered=" + delivered
                + " buffered=" + buffered);
    }

    /**
     * Buffers an op for offline delivery.
     * <p>
     * When the recipient reconnects, they call fetch-pending which
     * returns all buffered ops in Lamport clock order.
     */
    private void bufferOp(String noteId,
                           String recipientUserId,
                           String opPayload,
                           String senderJid,
                           long lamportClock) {
        logger.info("Buffering the CRDT OP's");
        // Check buffer size to prevent DoS
        if (countBufferedOps(noteId, recipientUserId) >= MAX_BUFFERED_OPS) {
            logger.warning("CRDT buffer full for userId=" + recipientUserId
                    + " noteId=" + noteId + " - dropping op");
            return;
        }

        String sqlOld = """
            INSERT INTO note_pending_ops (
                note_id, recipient_user_id, op_payload,
                sender_jid, lamport_clock, created_at
            ) VALUES (?::uuid, ?, ?, ?, ?, NOW())
            """;

        String sql = """
            INSERT INTO note_pending_ops (
                note_id, recipient_user_id, op_payload,
                sender_jid, lamport_clock, created_at
            ) VALUES (?, ?, ?, ?, ?, NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, recipientUserId);
            stmt.setString(3, opPayload);
            stmt.setString(4, senderJid);
            stmt.setLong(5, lamportClock);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("bufferOp error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Handle: Fetch pending ops (called on reconnect)
    // =========================================================================

    /**
     * Returns all buffered ops for this user across all their notes.
     * Ops are returned in Lamport clock order (per note).
     * Deletes them atomically to prevent duplicate delivery.
     */
    private void handleFetchPending(String iqId, Session session) {
        String userId = extractUserId(session.getContactId());

        String sql = """
            DELETE FROM note_pending_ops
            WHERE recipient_user_id = ?
            RETURNING
                note_id,
                op_payload,
                sender_jid,
                lamport_clock,
                created_at
            """;

        List<PendingOp> ops = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ops.add(new PendingOp(
                            rs.getString("note_id"),
                            rs.getString("op_payload"),
                            rs.getString("sender_jid"),
                            rs.getLong("lamport_clock"),
                            rs.getString("created_at")
                    ));
                }
            }
            conn.commit();

        } catch (SQLException e) {
            logger.severe("handleFetchPending error: " + e.getMessage());
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Sort by note_id then lamport_clock for consistent client processing
        ops.sort(Comparator.comparing(PendingOp::noteId)
                .thenComparingLong(PendingOp::lamportClock));

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<crdt-note xmlns='%s' action='pending-ops' count='%d'>",
            escapeXml(iqId), CRDT_NS, ops.size()
        ));

        for (PendingOp op : ops) {
            xml.append(String.format(
                "<pending-op note_id='%s' clock='%d'" +
                " from='%s' created_at='%s'>%s</pending-op>",
                op.noteId(),
                op.lamportClock(),
                escapeXml(op.senderJid()),
                op.createdAt(),
                op.opPayload()  // Opaque - client will parse
            ));
        }

        xml.append("</crdt-note></iq>");
        session.writeXML(xml.toString());

        logger.info("Delivered " + ops.size()
                + " pending CRDT ops to userId=" + userId);
    }

    // =========================================================================
    // Handle: Create note
    // =========================================================================

    /**
     * Registers a new note. Server stores only metadata.
     * The content lives entirely on client devices as CRDT.
     */
    private void handleCreate1(NoteRequest req, String iqId, Session session) {
        logger.info("Creating notes for CRDT");
        if (req.title() == null || req.conversationJid() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String creatorId = extractUserId(session.getContactId());

        String createSql = """
            INSERT INTO note_metadata (
                conversation_jid, title, creator_user_id
            ) VALUES (?, ?, ?)
            RETURNING note_id::text
            """;

        String addParticipantSql = """
            INSERT INTO note_participants (note_id, user_id)
            VALUES (?::uuid, ?)
            ON CONFLICT (note_id, user_id) DO NOTHING
            """;

        String noteId;

        try (Connection conn = pool.getConnection()) {

            try (PreparedStatement stmt =
                         conn.prepareStatement(createSql)) {
                stmt.setString(1, req.conversationJid());
                stmt.setString(2, req.title());
                stmt.setString(3, creatorId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sendError(session, iqId, "internal-server-error");
                        return;
                    }
                    noteId = rs.getString(1);
                }
            }

            // Add creator as participant
            try (PreparedStatement stmt =
                         conn.prepareStatement(addParticipantSql)) {
                stmt.setString(1, noteId);
                stmt.setString(2, creatorId);
                stmt.executeUpdate();
            }

            // Add invited participants
            if (req.participantIds() != null) {
                try (PreparedStatement stmt =
                             conn.prepareStatement(addParticipantSql)) {
                    for (String participantId : req.participantIds()) {
                        stmt.setString(1, noteId);
                        stmt.setString(2, participantId);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            conn.commit();

        } catch (SQLException e) {
            logger.severe("handleCreate error: " + e.getMessage());
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Confirm to creator
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<crdt-note xmlns='%s' action='created'>" +
            "<note_id>%s</note_id>" +
            "</crdt-note></iq>",
            escapeXml(iqId), CRDT_NS, noteId
        ));

        // Notify invited participants
        if (req.participantIds() != null) {
            notifyParticipants(noteId, req.title(), creatorId,
                    session.getContactId(), req.participantIds());
        }

        logger.info("Note created: noteId=" + noteId
                + " creator=" + creatorId
                + " participants=" +
                (req.participantIds() != null ? req.participantIds().size() : 0));
    }

    private void handleCreate(NoteRequest req, String iqId, Session session) {

        if (req.title() == null || req.conversationJid() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String creatorId = extractUserId(session.getContactId());
        String noteId = UUID.randomUUID().toString();

        String createSql = """
        INSERT INTO note_metadata (
            note_id, conversation_jid, title, creator_user_id
        ) VALUES (?, ?, ?, ?)
        """;

        String addParticipantSql = """
        INSERT INTO note_participants (note_id, user_id)
        VALUES (?, ?)
        ON DUPLICATE KEY UPDATE user_id = user_id
        """;

        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Create the note
                try (PreparedStatement stmt = conn.prepareStatement(createSql)) {
                    stmt.setString(1, noteId);
                    stmt.setString(2, req.conversationJid());
                    stmt.setString(3, req.title());
                    stmt.setString(4, creatorId);
                    stmt.executeUpdate();
                }

                // Add creator as participant
                try (PreparedStatement stmt = conn.prepareStatement(addParticipantSql)) {
                    stmt.setString(1, noteId);
                    stmt.setString(2, creatorId);
                    stmt.executeUpdate();
                }

                // Add invited participants
                if (req.participantIds() != null && !req.participantIds().isEmpty()) {
                    try (PreparedStatement stmt = conn.prepareStatement(addParticipantSql)) {
                        for (String participantId : req.participantIds()) {
                            stmt.setString(1, noteId);
                            stmt.setString(2, participantId);
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                }

                conn.commit();

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException e) {
            logger.severe("handleCreate error: " + e.getMessage());
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Confirm to creator
        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<crdt-note xmlns='%s' action='created'>" +
                        "<note_id>%s</note_id>" +
                        "</crdt-note></iq>",
                escapeXml(iqId), CRDT_NS, noteId
        ));

        // Notify invited participants
        if (req.participantIds() != null && !req.participantIds().isEmpty()) {
            notifyParticipants(
                    noteId,
                    req.title(),
                    creatorId,
                    session.getContactId(),
                    req.participantIds()
            );
        }

        logger.info("Note created: noteId=" + noteId
                + " creator=" + creatorId
                + " participants="
                + (req.participantIds() != null ? req.participantIds().size() : 0));
    }

    // =========================================================================
    // Handle: Invite participant
    // =========================================================================

    private void handleInvite(NoteRequest req, String iqId, Session session) {
        if (req.noteId() == null || req.newParticipantId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String inviterId = extractUserId(session.getContactId());

        // Verify inviter is a participant
        if (!isParticipant(req.noteId(), inviterId)) {
            sendError(session, iqId, "forbidden");
            return;
        }

        String sql = """
            INSERT INTO note_participants (note_id, user_id)
            VALUES (?::uuid, ?)
            ON CONFLICT (note_id, user_id) DO NOTHING
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, req.noteId());
            stmt.setString(2, req.newParticipantId());
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // Notify new participant + existing ones
        String title = getNoteTitle(req.noteId());
        notifyParticipants(req.noteId(), title, inviterId,
                session.getContactId(),
                List.of(req.newParticipantId()));
    }

    private void handleRemoveParticipant(NoteRequest req,
                                          String iqId,
                                          Session session) {
        if (req.noteId() == null || req.removeUserId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String requesterId = extractUserId(session.getContactId());

        // Only creator can remove others; anyone can remove themselves
        if (!requesterId.equals(req.removeUserId())
                && !isCreator(req.noteId(), requesterId)) {
            sendError(session, iqId, "forbidden");
            return;
        }

        String sql = """
            DELETE FROM note_participants
            WHERE note_id::text = ? AND user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, req.noteId());
            stmt.setString(2, req.removeUserId());
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Delete their pending ops
        deletePendingOpsForUser(req.noteId(), req.removeUserId());

        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // Notify removed user
        String removedJid = getUserJid(req.removeUserId());
        if (removedJid != null) {
            String notification = String.format(
                "<message type='headline'>" +
                "<crdt-note xmlns='%s' action='removed'>" +
                "<note_id>%s</note_id>" +
                "</crdt-note></message>",
                CRDT_NS, req.noteId()
            );
            registry.getByContactId(removedJid)
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(notification));
        }
    }

    // =========================================================================
    // Handle: Delete note
    // =========================================================================

    private void handleDelete(NoteRequest req, String iqId, Session session) {
        if (req.noteId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String userId = extractUserId(session.getContactId());
        if (!isCreator(req.noteId(), userId)) {
            sendError(session, iqId, "forbidden");
            return;
        }

        String sql = """
            UPDATE note_metadata
            SET deleted_at = NOW()
            WHERE note_id::text = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, req.noteId());
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // Notify all participants
        List<String> participants = getOtherParticipants(req.noteId(), userId);
        String notification = String.format(
            "<message type='headline'>" +
            "<crdt-note xmlns='%s' action='deleted'>" +
            "<note_id>%s</note_id>" +
            "</crdt-note></message>",
            CRDT_NS, req.noteId()
        );

        for (String participantId : participants) {
            String jid = getUserJid(participantId);
            if (jid != null) {
                registry.getByContactId(jid)
                        .filter(Session::isAuthenticated)
                        .ifPresent(s -> s.writeXML(notification));
            }
        }
    }

    // =========================================================================
    // Handle: List notes
    // =========================================================================

    private void handleListNotes(String iqId, Session session) {
        String userId = extractUserId(session.getContactId());

        String sql = """
            SELECT
                nm.note_id,
                nm.title,
                nm.conversation_jid,
                nm.creator_user_id,
                nm.updated_at
            FROM note_metadata nm
            INNER JOIN note_participants np
                ON np.note_id = nm.note_id
               AND np.user_id = ?
            WHERE nm.deleted_at IS NULL
            ORDER BY nm.updated_at DESC
            """;

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<crdt-note xmlns='%s' action='list'>",
            escapeXml(iqId), CRDT_NS
        ));

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    xml.append(String.format(
                        "<note note_id='%s' title='%s'" +
                        " conversation_jid='%s' creator='%s'" +
                        " updated_at='%s'/>",
                        rs.getString("note_id"),
                        escapeXml(rs.getString("title")),
                        escapeXml(rs.getString("conversation_jid")),
                        escapeXml(rs.getString("creator_user_id")),
                        rs.getString("updated_at")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("handleListNotes error: " + e.getMessage());
        }

        xml.append("</crdt-note></iq>");
        session.writeXML(xml.toString());
    }

    // =========================================================================
    // Database helpers
    // =========================================================================

    private List<String> getOtherParticipants(String noteId, String excludeUserId) {
        String sql = """
            SELECT user_id FROM note_participants
            WHERE note_id = ? AND user_id != ?
            """;

        List<String> result = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, excludeUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }

        } catch (SQLException e) {
            logger.warning("getOtherParticipants error: " + e.getMessage());
        }
        return result;
    }

    private boolean isParticipant(String noteId, String userId) {
        String sql = """
            SELECT 1 FROM note_participants
            WHERE note_id = ? AND user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isCreator(String noteId, String userId) {
        String sql = """
            SELECT 1 FROM note_metadata
            WHERE note_id = ? AND creator_user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private int countBufferedOps(String noteId, String userId) {
        String sql = """
            SELECT COUNT(*) FROM note_pending_ops
            WHERE note_id = ? AND recipient_user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private String getNoteTitle(String noteId) {
        String sql = """
            SELECT title FROM note_metadata WHERE note_id = ?
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, noteId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : "Untitled";
            }
        } catch (SQLException e) {
            return "Untitled";
        }
    }

    private String getUserJid(String userId) {
        String sql = "SELECT jid FROM users WHERE user_id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private void deletePendingOpsForUser(String noteId, String userId) {
        String sql = """
            DELETE FROM note_pending_ops
            WHERE note_id = ? AND recipient_user_id = ?
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, noteId);
            stmt.setString(2, userId);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            logger.warning("deletePendingOpsForUser error: " + e.getMessage());
        }
    }

    private void notifyParticipants(String noteId,
                                      String title,
                                      String inviterUserId,
                                      String inviterJid,
                                      List<String> participantIds) {
        String notification = String.format(
            "<message type='headline'>" +
            "<crdt-note xmlns='%s' action='invited'>" +
            "<note_id>%s</note_id>" +
            "<title>%s</title>" +
            "<inviter>%s</inviter>" +
            "</crdt-note></message>",
            CRDT_NS,
            noteId,
            escapeXml(title),
            escapeXml(inviterJid)
        );

        for (String participantId : participantIds) {
            String jid = getUserJid(participantId);
            logger.info("Invited participant JID is: "+jid);
            if (jid == null) continue;

            registry.getByContactId(jid)
                    .filter(Session::isAuthenticated)
                    .ifPresentOrElse(s -> s.writeXML(notification), () -> {
                        logger.info("The participant is offline,  caching the notes invitation");
                        // TODO: Buffer the invitation till the user reconnects
                    });
        }
    }

    private void cacheNoteNotifications(String noteId, String conversationJid, String title, String creatorUid){
        String sql = "INSERT INTO pending_notes_notification ";
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private NoteRequest parseRequest(XMLEventReader reader) {
        String action           = null;
        String noteId           = null;
        String title            = null;
        String conversationJid  = null;
        String opPayload        = null;
        long   lamportClock     = 0;
        String newParticipantId = null;
        String removeUserId     = null;
        List<String> participantIds = new ArrayList<>();

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    if ("crdt-note".equals(name) && CRDT_NS.equals(ns)) {
                        action = getAttr(se, "action");
                    }

                    if ("crdt-op".equals(name) && CRDT_NS.equals(ns)) {
                        action = "op";
                        noteId = getAttr(se, "note_id");
                        String clockStr = getAttr(se, "clock");
                        if (clockStr != null) {
                            try { lamportClock = Long.parseLong(clockStr); }
                            catch (NumberFormatException ignored) {}
                        }
                        opPayload = readTextRaw(reader);
                        depth--;
                    }

                    switch (name) {
                        case "note_id"          -> noteId = readText(reader);
                        case "title"            -> title  = readText(reader);
                        case "conversation_jid" -> conversationJid = readText(reader);
                        case "user_id"          -> {
                            String uid = readText(reader);
                            if (!uid.isEmpty()) participantIds.add(uid);
                        }
                        case "new_participant"  -> newParticipantId = readText(reader);
                        case "remove_user"      -> removeUserId = readText(reader);
                    }
                }

                if (event.isEndElement()) depth--;

                if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("iq"))  break;
            }
        } catch (XMLStreamException e) {
            logger.warning("parseRequest error: " + e.getMessage());
            return null;
        }

        return new NoteRequest(action, noteId, title, conversationJid,
                opPayload, lamportClock,
                participantIds.isEmpty() ? null : participantIds,
                newParticipantId, removeUserId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Reads text preserving whitespace and structure.
     * Used for op payloads (JSON that must not be modified).
     */
    private String readTextRaw(XMLEventReader reader) {
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
        return sb.toString(); // NO .trim() - preserve exact bytes
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

    private record NoteRequest(
            String action,
            String noteId,
            String title,
            String conversationJid,
            String opPayload,
            long lamportClock,
            List<String> participantIds,
            String newParticipantId,
            String removeUserId
    ) {}

    private record PendingOp(
            String noteId,
            String opPayload,
            String senderJid,
            long lamportClock,
            String createdAt
    ) {}
}