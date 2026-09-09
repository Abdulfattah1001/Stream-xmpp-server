package streammessenger.stanza;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.sql.*;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles scheduled message operations.
 * <p>
 * Custom namespace: urn:xmpp:schedule:0
 * <p>
 * WHAT IT DOES:
 * ─────────────
 * User sends: "Send this message to Alice at 09:00 tomorrow"
 * <p>
 * Server stores the encrypted message with a scheduled_for timestamp.
 * Background scheduler fires at that time, routes the message as if
 * the user just sent it.
 * <p>
 * The message content is encrypted by the client before scheduling.
 * Server stores and delivers ciphertext only.
 * <p>
 * OPERATIONS:
 * ───────────
 *   schedule → Store a message for later delivery
 *   cancel   → Cancel a pending scheduled message
 *   list     → List all pending scheduled messages
 * <p>
 * Example - Schedule:
 *   <iq type='set' id='sc1'>
 *     <schedule xmlns='urn:xmpp:schedule:0' action='schedule'>
 *       <to>u_abc@domain.com</to>
 *       <send_at>2024-12-25T09:00:00Z</send_at>
 *       <encrypted iv='BASE64_IV' msg_type='text'>BASE64_CIPHERTEXT</encrypted>
 *     </schedule>
 *   </iq>
 * <p>
 * Example - Cancel:
 *   <iq type='set' id='sc2'>
 *     <schedule xmlns='urn:xmpp:schedule:0' action='cancel'>
 *       <message_id>uuid-here</message_id>
 *     </schedule>
 *   </iq>
 */
public final class ScheduledMessageHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(ScheduledMessageHandler.class.getName());

    private static final String SCHEDULE_NS = "urn:xmpp:schedule:0";

    // Max 100 pending scheduled messages per user
    private static final int MAX_SCHEDULED_PER_USER = 100;

    // Max schedule time: 1 year from now
    private static final long MAX_SCHEDULE_MS =
            365L * 24 * 60 * 60 * 1000;

    private final ConnectionPool pool;
    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final EncryptedMessageHandler encryptedMessageHandler;

    // Background scheduler that fires scheduled messages
    private final ScheduledExecutorService scheduler;

    // In-memory index of pending tasks: messageId → ScheduledFuture
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingTasks =
            new ConcurrentHashMap<>();

    public ScheduledMessageHandler(ConnectionPool pool,
                                    DatabaseManager db,
                                    SessionRegistry registry,
                                    EncryptedMessageHandler messageHandler) {
        this.pool           = pool;
        this.db             = db;
        this.registry       = registry;
        this.encryptedMessageHandler = messageHandler;

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduled-msg");
            t.setDaemon(true);
            return t;
        });

        // Re-schedule any pending messages from DB on startup
        loadPendingFromDatabase();
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
        ScheduleRequest req = parseRequest(reader);

        if (req == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (req.action()) {
            case "schedule" -> handleSchedule(req, iqId, session);
            case "cancel"   -> handleCancel(req, iqId, session);
            case "list"     -> handleList(iqId, session);
            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Schedule
    // =========================================================================

    private void handleSchedule(ScheduleRequest req,
                                  String iqId,
                                  Session session) {

        String userId = extractUserId(session.getContactId());

        // Validate send_at
        Instant sendAt;
        try {
            sendAt = Instant.parse(req.sendAt());
        } catch (Exception e) {
            sendValidationError(session, iqId,
                "Invalid send_at format. Use ISO-8601: 2024-12-25T09:00:00Z");
            return;
        }

        long delayMs = sendAt.toEpochMilli() - System.currentTimeMillis();

        if (delayMs < 60_000) { // At least 1 minute in future
            sendValidationError(session, iqId,
                "send_at must be at least 1 minute in the future");
            return;
        }

        if (delayMs > MAX_SCHEDULE_MS) {
            sendValidationError(session, iqId,
                "send_at cannot be more than 1 year in the future");
            return;
        }

        // Check per-user limit
        if (countPendingScheduled(userId) >= MAX_SCHEDULED_PER_USER) {
            sendValidationError(session, iqId,
                "Maximum " + MAX_SCHEDULED_PER_USER +
                " scheduled messages per user");
            return;
        }

        String messageId = UUID.randomUUID().toString();

        // Store in database
        boolean stored = storeScheduledMessage(
                messageId, userId, session.getContactId(),
                req.to(), req.msgType(), req.encryptedContent(),
                req.iv(), req.mediaStorageKey(), req.mimeType(),
                req.fileSizeBytes(), sendAt
        );

        if (!stored) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Schedule the in-memory task
        scheduleTask(messageId, session.getContactId(),
                req.to(), req.msgType(), req.encryptedContent(),
                req.iv(), req.mediaStorageKey(), req.mimeType(),
                req.fileSizeBytes(), delayMs);

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<schedule xmlns='%s' action='scheduled'>" +
            "<message_id>%s</message_id>" +
            "<send_at>%s</send_at>" +
            "</schedule></iq>",
            escapeXml(iqId), SCHEDULE_NS,
            messageId, req.sendAt()
        ));

        logger.info("Message scheduled: userId=" + userId
                + " messageId=" + messageId
                + " sendAt=" + req.sendAt());
    }

    // =========================================================================
    // Cancel
    // =========================================================================

    private void handleCancel(ScheduleRequest req,
                               String iqId,
                               Session session) {

        if (req.messageId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String userId = extractUserId(session.getContactId());

        // Cancel in-memory task
        ScheduledFuture<?> task = pendingTasks.remove(req.messageId());
        if (task != null) task.cancel(false);

        // Mark as cancelled in DB
        boolean cancelled = cancelScheduledMessage(
                req.messageId(), userId);

        if (!cancelled) {
            sendError(session, iqId, "item-not-found");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<schedule xmlns='%s' action='cancelled'>" +
            "<message_id>%s</message_id>" +
            "</schedule></iq>",
            escapeXml(iqId), SCHEDULE_NS,
            escapeXml(req.messageId())
        ));
    }

    // =========================================================================
    // List
    // =========================================================================

    private void handleList(String iqId, Session session) {
        String userId = extractUserId(session.getContactId());
        List<ScheduledMessageRecord> pending = listPending(userId);

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<schedule xmlns='%s' action='list' count='%d'>",
            escapeXml(iqId), SCHEDULE_NS, pending.size()
        ));

        for (ScheduledMessageRecord msg : pending) {
            xml.append(String.format(
                "<item message_id='%s' to='%s'" +
                " msg_type='%s' send_at='%s'/>",
                msg.messageId(), escapeXml(msg.to()),
                msg.msgType(), msg.sendAt()
            ));
        }

        xml.append("</schedule></iq>");
        session.writeXML(xml.toString());
    }

    // =========================================================================
    // Task execution
    // =========================================================================

    /**
     * Schedules an in-memory task to fire at the right time.
     * When it fires: delivers the message as if sent now.
     */
    private void scheduleTask(String messageId,
                               String fromJid,
                               String toJid,
                               String msgType,
                               String encryptedContent,
                               String iv,
                               String mediaStorageKey,
                               String mimeType,
                               long fileSizeBytes,
                               long delayMs) {

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingTasks.remove(messageId);

            logger.info("Firing scheduled message: " + messageId);

            // Route the message now
            String toContactId = toJid.contains("/")
                    ? toJid.substring(0, toJid.indexOf('/'))
                    : toJid;

            // Build a fake stanza and route it
            // Find the best available recipient session
            registry.getByContactId(toContactId).ifPresentOrElse(
                    recipientSession -> {
                        String stanza = buildScheduledStanza(
                                messageId, fromJid, toContactId,
                                msgType, encryptedContent, iv,
                                mediaStorageKey, mimeType, fileSizeBytes
                        );
                        boolean delivered = recipientSession.writeXML(stanza);
                        if (!delivered) {
                            // Recipient went offline between schedule and fire
                            db.storeEncryptedMessage(
                                    fromJid, toContactId, messageId,
                                    msgType, encryptedContent, iv,
                                    mediaStorageKey, null, mimeType,
                                    fileSizeBytes, null
                            );
                        }
                    },
                    () -> {
                        // Recipient is offline - store for delivery
                        db.storeEncryptedMessage(
                                fromJid, toContactId, messageId,
                                msgType, encryptedContent, iv,
                                mediaStorageKey, null, mimeType,
                                fileSizeBytes, null
                        );
                    }
            );

            // Mark as sent in DB
            markScheduledSent(messageId);

        }, delayMs, TimeUnit.MILLISECONDS);

        pendingTasks.put(messageId, future);
    }

    /**
     * On server startup, loads all pending scheduled messages from DB
     * and reschedules them in memory.
     * Handles the case where the server restarted while messages were pending.
     */
    private void loadPendingFromDatabase() {
        String sql = """
    SELECT
        CAST(message_id AS CHAR) AS message_id,
        from_jid,
        to_jid,
        message_type,
        encrypted_content,
        iv,
        media_storage_key,
        mime_type,
        file_size_bytes,
        scheduled_for
    FROM scheduled_messages
    WHERE sent_at IS NULL
      AND cancelled_at IS NULL
      AND scheduled_for > NOW()
    """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            int count = 0;
            while (rs.next()) {
                String messageId  = rs.getString("message_id");
                Timestamp sendAt  = rs.getTimestamp("scheduled_for");
                long delayMs = sendAt.getTime() - System.currentTimeMillis();

                if (delayMs < 0) delayMs = 0; // Past due - fire immediately

                scheduleTask(
                        messageId,
                        rs.getString("from_jid"),
                        rs.getString("to_jid"),
                        rs.getString("message_type"),
                        rs.getString("encrypted_content"),
                        rs.getString("iv"),
                        rs.getString("media_storage_key"),
                        rs.getString("mime_type"),
                        rs.getLong("file_size_bytes"),
                        delayMs
                );
                count++;
            }

            if (count > 0) {
                logger.info("Loaded " + count
                        + " pending scheduled messages from database");
            }

        } catch (SQLException e) {
            logger.severe("loadPendingFromDatabase error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Database operations
    // =========================================================================

    private boolean storeScheduledMessage(String messageId,
                                           String fromUserId,
                                           String fromJid,
                                           String toJid,
                                           String msgType,
                                           String encryptedContent,
                                           String iv,
                                           String mediaStorageKey,
                                           String mimeType,
                                           long fileSizeBytes,
                                           Instant sendAt) {
        String sql = """
            INSERT INTO scheduled_messages (
                message_id, from_user_id, from_jid, to_jid,
                message_type, encrypted_content, iv,
                media_storage_key, mime_type, file_size_bytes,
                scheduled_for, created_at
            ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.setString(2, fromUserId);
            stmt.setString(3, fromJid);
            stmt.setString(4, toJid);
            stmt.setString(5, msgType);
            stmt.setString(6, encryptedContent);
            stmt.setString(7, iv);
            stmt.setString(8, mediaStorageKey);
            stmt.setString(9, mimeType);
            stmt.setLong(10, fileSizeBytes);
            stmt.setString(11, sendAt.toString());
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("storeScheduledMessage error: " + e.getMessage());
            return false;
        }
    }

    private boolean cancelScheduledMessage(String messageId,
                                            String userId) {
        String sql = """
            UPDATE scheduled_messages
            SET cancelled_at = NOW()
            WHERE message_id::text = ?
              AND from_user_id = ?
              AND sent_at IS NULL
              AND cancelled_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.setString(2, userId);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("cancelScheduledMessage error: " + e.getMessage());
            return false;
        }
    }

    private void markScheduledSent(String messageId) {
        String sql = """
            UPDATE scheduled_messages
            SET sent_at = NOW()
            WHERE message_id::text = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("markScheduledSent error: " + e.getMessage());
        }
    }

    private int countPendingScheduled(String userId) {
        String sql = """
            SELECT COUNT(*) FROM scheduled_messages
            WHERE from_user_id = ?
              AND sent_at IS NULL
              AND cancelled_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        } catch (SQLException e) {
            return 0;
        }
    }

    private List<ScheduledMessageRecord> listPending(String userId) {
        String sql = """
            SELECT
                message_id::text,
                to_jid,
                message_type,
                scheduled_for::text
            FROM scheduled_messages
            WHERE from_user_id = ?
              AND sent_at IS NULL
              AND cancelled_at IS NULL
              AND scheduled_for > NOW()
            ORDER BY scheduled_for ASC
            """;

        List<ScheduledMessageRecord> result = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new ScheduledMessageRecord(
                            rs.getString("message_id"),
                            rs.getString("to_jid"),
                            rs.getString("message_type"),
                            rs.getString("scheduled_for")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("listPending error: " + e.getMessage());
        }

        return result;
    }

    // =========================================================================
    // XML helpers
    // =========================================================================

    private String buildScheduledStanza(String messageId,
                                         String fromJid,
                                         String toJid,
                                         String msgType,
                                         String encrypted,
                                         String iv,
                                         String storageKey,
                                         String mime,
                                         long size) {
        return String.format(
            "<message id='%s' from='%s' to='%s' type='chat'>" +
            "<encrypted xmlns='urn:xmpp:e2ee:0'" +
            " msg_type='%s' iv='%s'%s%s%s>%s</encrypted>" +
            "<delay xmlns='urn:xmpp:delay' stamp='%s'/>" +
            "</message>",
            messageId, escapeXml(fromJid), escapeXml(toJid),
            msgType, iv,
            storageKey != null ? " storage_key='" + storageKey + "'" : "",
            mime != null ? " mime='" + mime + "'" : "",
            size > 0 ? " size='" + size + "'" : "",
            encrypted,
            Instant.now().toString()
        );
    }

    private ScheduleRequest parseRequest(XMLEventReader reader) {
        String action          = null;
        String to              = null;
        String sendAt          = null;
        String messageId       = null;
        String msgType         = "text";
        String encryptedContent = null;
        String iv              = null;
        String mediaStorageKey = null;
        String mimeType        = null;
        long   fileSizeBytes   = 0;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    if ("schedule".equals(name)
                            && SCHEDULE_NS.equals(ns)) {
                        action = getAttr(se, "action");
                    }

                    switch (name) {
                        case "to"         -> to         = readText(reader);
                        case "send_at"    -> sendAt     = readText(reader);
                        case "message_id" -> messageId  = readText(reader);
                        case "encrypted"  -> {
                            iv              = getAttr(se, "iv");
                            msgType         = getAttr(se, "msg_type");
                            mediaStorageKey = getAttr(se, "storage_key");
                            mimeType        = getAttr(se, "mime");
                            String sz       = getAttr(se, "size");
                            if (sz != null) {
                                try { fileSizeBytes = Long.parseLong(sz); }
                                catch (NumberFormatException ignored) {}
                            }
                            encryptedContent = readText(reader);
                            depth--;
                        }
                    }
                }

                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing schedule request: " + e.getMessage());
            return null;
        }

        return new ScheduleRequest(action, to, sendAt, messageId,
                msgType, encryptedContent, iv,
                mediaStorageKey, mimeType, fileSizeBytes);
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

    private void sendValidationError(Session s, String iqId, String text) {
        s.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='modify'>" +
            "<bad-request xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "<text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>%s</text>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            escapeXml(text)
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

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record ScheduleRequest(
            String action,
            String to,
            String sendAt,
            String messageId,
            String msgType,
            String encryptedContent,
            String iv,
            String mediaStorageKey,
            String mimeType,
            long fileSizeBytes
    ) {}

    private record ScheduledMessageRecord(
            String messageId,
            String to,
            String msgType,
            String sendAt
    ) {}
}