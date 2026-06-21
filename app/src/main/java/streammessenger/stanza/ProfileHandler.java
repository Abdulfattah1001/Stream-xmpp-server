package streammessenger.stanza;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles profile updates (display name, avatar, about).
 * <p>
 * Namespace: urn:xmpp:profile:0
 * <p>
 * Operations:
 *   update  → Change own profile fields
 *   get     → Fetch specific user's profile
 *   sync    → Get all profile changes since timestamp
 * <p>
 * Broadcast on update:
 *   1. Find all users with sender in their roster (sub='from' or 'both')
 *   2. Online users: send <profile-update> stanza immediately
 *   3. Offline users: queue in profile_update_queue
 *   4. On reconnect, queued updates delivered as part of bind sequence
 */
public final class ProfileHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(ProfileHandler.class.getName());

    private static final String PROFILE_NS = "urn:xmpp:profile:0";

    private final ConnectionPool pool;
    private final DatabaseManager db;
    private final SessionRegistry registry;

    public ProfileHandler(ConnectionPool pool,
                          DatabaseManager db,
                          SessionRegistry registry) {
        this.pool = pool;
        this.db = db;
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

        String iqId = getAttr(element, "id");
        ProfileRequest req = parseRequest(reader);

        switch (req.action()) {
            case "update" -> handleUpdate(req, iqId, session);
            case "get"    -> handleGet(req, iqId, session);
            case "sync"   -> handleSync(req, iqId, session);
        }
    }

    // =========================================================================
    // Update profile
    // =========================================================================

    /**
     * Updates the user's profile and broadcasts to contacts.
     */
    private void handleUpdate(ProfileRequest req,
                               String iqId,
                               Session session) {
        String userId = extractUserId(session.getContactId());

        // 1. Update database
        boolean updated = updateUserProfile(userId, req);

        if (!updated) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // 2. Confirm to user
        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // 3. Find all contacts who should be notified
        //    Anyone with us in their roster (sub='from' or 'both')
        List<ContactToNotify> contacts = findContactsToNotify(userId);

        // 4. Build update stanza
        String updateStanza = buildProfileUpdateStanza(
                session.getContactId(), req);

        int onlineDelivered = 0;
        int queuedOffline = 0;

        for (ContactToNotify contact : contacts) {
            boolean delivered = registry.getByContactId(contact.jid())
                    .filter(Session::isAuthenticated)
                    .map(s -> s.writeXML(updateStanza))
                    .orElse(false);

            if (delivered) {
                onlineDelivered++;
            } else {
                queueProfileUpdate(userId, contact.userId(),
                        req.updateType(), req.newValue());
                queuedOffline++;
            }
        }

        logger.info("Profile update broadcast: userId=" + userId
                + " online=" + onlineDelivered
                + " queued=" + queuedOffline);
    }

    // =========================================================================
    // Sync on reconnect (returns pending profile updates)
    // =========================================================================

    /**
     * Called by client on reconnect to fetch pending profile updates.
     * Returns and deletes queued updates atomically.
     */
    private void handleSync(ProfileRequest req,
                             String iqId,
                             Session session) {
        String userId = extractUserId(session.getContactId());

        String sql = """
            DELETE FROM profile_update_queue
            WHERE recipient_user_id = ?
            RETURNING
                subject_user_id,
                update_type,
                new_value,
                (SELECT jid FROM users WHERE user_id = subject_user_id) AS subject_jid,
                created_at::text
            """;

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<profile xmlns='%s' action='sync'>",
            escapeXml(iqId), PROFILE_NS
        ));

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    xml.append(String.format(
                        "<update subject='%s' type='%s' at='%s'>%s</update>",
                        escapeXml(rs.getString("subject_jid")),
                        escapeXml(rs.getString("update_type")),
                        rs.getString("created_at"),
                        escapeXml(rs.getString("new_value") != null
                                ? rs.getString("new_value") : "")
                    ));
                }
            }
            conn.commit();

        } catch (SQLException e) {
            logger.severe("handleSync error: " + e.getMessage());
        }

        xml.append("</profile></iq>");
        session.writeXML(xml.toString());
    }

    // =========================================================================
    // Get current profile
    // =========================================================================

    private void handleGet(ProfileRequest req,
                            String iqId,
                            Session session) {
        // Returns profile of req.targetJid()
        String targetJid = req.targetJid() != null
                ? req.targetJid()
                : session.getContactId();

        // ... fetch and return XML
    }

    // =========================================================================
    // Database operations
    // =========================================================================

    private boolean updateUserProfile(String userId, ProfileRequest req) {
        String sql = switch (req.updateType()) {
            case "display_name" -> "UPDATE users SET display_name=?, updated_at=NOW() WHERE user_id=?";
            case "avatar"       -> "UPDATE users SET avatar_url=?, updated_at=NOW() WHERE user_id=?";
            case "about"        -> "UPDATE users SET about=?, updated_at=NOW() WHERE user_id=?";
            default -> null;
        };

        if (sql == null) return false;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, req.newValue());
            stmt.setString(2, userId);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("updateUserProfile error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Finds all users with this user in their roster.
     * They should receive profile updates.
     */
    private List<ContactToNotify> findContactsToNotify(String userId) {
        String sql = """
            SELECT
                ri.owner_user_id AS user_id,
                u.jid
            FROM roster_items ri
            INNER JOIN users u ON u.user_id = ri.owner_user_id
            WHERE ri.contact_user_id = ?
              AND ri.subscription IN ('from', 'both')
              AND ri.blocked = false
              AND u.active = true
              AND u.deleted_at IS NULL
            """;

        List<ContactToNotify> result = new java.util.ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new ContactToNotify(
                            rs.getString("user_id"),
                            rs.getString("jid")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("findContactsToNotify error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Queues a profile update for offline delivery.
     * Squashed: only latest update per (subject, recipient, type) kept.
     */
    private void queueProfileUpdate(String subjectUserId,
                                     String recipientUserId,
                                     String updateType,
                                     String newValue) {
        String sql = """
            INSERT INTO profile_update_queue
                (subject_user_id, recipient_user_id, update_type, new_value, created_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (subject_user_id, recipient_user_id, update_type)
            DO UPDATE SET
                new_value  = EXCLUDED.new_value,
                created_at = NOW()
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, subjectUserId);
            stmt.setString(2, recipientUserId);
            stmt.setString(3, updateType);
            stmt.setString(4, newValue);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("queueProfileUpdate error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String buildProfileUpdateStanza(String fromJid,
                                              ProfileRequest req) {
        return String.format(
            "<message from='%s' type='headline'>" +
            "<profile-update xmlns='%s' type='%s'>" +
            "<value>%s</value>" +
            "</profile-update>" +
            "</message>",
            escapeXml(fromJid),
            PROFILE_NS,
            escapeXml(req.updateType()),
            escapeXml(req.newValue() != null ? req.newValue() : "")
        );
    }

    private record ProfileRequest(
            String action,
            String updateType,
            String newValue,
            String targetJid
    ) {}

    private record ContactToNotify(String userId, String jid) {}

    private ProfileRequest parseRequest(XMLEventReader reader) { /* parse */ return null; }
    private String extractUserId(String jid) { /* impl */ return null; }
    private String getAttr(StartElement e, String name) { /* impl */ return null; }
    private void sendError(Session s, String id, String cond) { /* impl */ }
    private void consumeElement(XMLEventReader reader) { /* impl */ }
    private String escapeXml(String s) { /* impl */ return s; }
}