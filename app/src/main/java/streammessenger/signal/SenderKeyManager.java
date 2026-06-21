package streammessenger.signal;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.muc.service.GroupRegistry;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Server-side coordination for Signal Protocol Sender Key rotation
 * in group chats.
 *
 * The server doesn't see message content (E2E).
 * The server only coordinates KEY ROTATION events:
 *
 *   1. Tracks "sender key generations" per group
 *   2. On membership changes, increments generation and notifies all members
 *   3. Each client generates a new sender key for the new generation
 *   4. Clients distribute new sender keys to members of the new generation
 *
 * KEY DISTRIBUTION FLOW:
 * ──────────────────────
 *   Server: "Group X is now at generation N. Members are: [A, B, C]"
 *
 *   Client A: Generates new sender key for generation N
 *             Encrypts SenderKeyDistributionMessage for:
 *               - B's identity key
 *               - C's identity key
 *             Sends sealed-sender messages to B and C
 *
 *   Client B/C: Receives, decrypts, stores sender key under (groupId, A, N)
 *
 *   Now A can send messages encrypted with the new sender key.
 *   B and C can decrypt them.
 *   Anyone removed before generation N cannot decrypt.
 *
 * RACE CONDITION HANDLING:
 * ────────────────────────
 *   Member added at T1, generation = N+1
 *   Member sends message at T2 with OLD key (generation N)
 *   New member can't decrypt → asks for sender key for generation N
 *   Sender refuses (member doesn't have generation N rights)
 *   Sender uses generation N+1 key for future messages
 */
public final class SenderKeyManager {

    private static final Logger logger =
            Logger.getLogger(SenderKeyManager.class.getName());

    private static final String SIGNAL_NS = "urn:xmpp:signal:0";

    private final ConnectionPool pool;
    private final SessionRegistry sessionRegistry;
    private final GroupRegistry groupRegistry;

    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 20, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public SenderKeyManager(ConnectionPool pool,
                             SessionRegistry sessionRegistry,
                             GroupRegistry groupRegistry) {
        this.pool             = pool;
        this.sessionRegistry  = sessionRegistry;
        this.groupRegistry    = groupRegistry;
    }

    // =========================================================================
    // Membership change hooks
    // =========================================================================

    /**
     * Called when a member is added to a group.
     * Triggers a sender key rotation.
     */
    public void onMemberAdded(String groupId, String newMemberJid) {
        executor.execute(() -> rotateSenderKeys(
                groupId,
                RotationReason.MEMBER_ADDED,
                newMemberJid
        ));
    }

    /**
     * Called when a member is removed/banned.
     * Triggers immediate sender key rotation.
     * The removed member will not get the new key.
     */
    public void onMemberRemoved(String groupId, String removedMemberJid) {
        executor.execute(() -> rotateSenderKeys(
                groupId,
                RotationReason.MEMBER_REMOVED,
                removedMemberJid
        ));
    }

    /**
     * Called when membership approval mode changes
     * or other admin actions affecting key access.
     */
    public void onSecurityRelevantChange(String groupId, String reason) {
        executor.execute(() -> rotateSenderKeys(
                groupId,
                RotationReason.SECURITY_CHANGE,
                reason
        ));
    }

    // =========================================================================
    // Core rotation logic
    // =========================================================================

    /**
     * Increments the sender key generation for a group and notifies
     * all current members to rotate their sender keys.
     *
     * This is the server's role - the actual key generation and
     * distribution happens entirely on the clients (E2E).
     */
    private void rotateSenderKeys(String groupId,
                                    RotationReason reason,
                                    String triggerUserJid) {

        // 1. Get the new generation number
        int newGeneration = incrementGeneration(groupId, reason,
                triggerUserJid);
        if (newGeneration < 0) return;

        // 2. Fetch current member list
        List<MemberKeyInfo> currentMembers = fetchCurrentMembers(groupId);

        // 3. Build rotation notification
        String notification = buildRotationStanza(
                groupId, newGeneration, reason, currentMembers);

        // 4. Send to all online members
        // Offline members will receive it on reconnect via offline message
        for (MemberKeyInfo member : currentMembers) {
            sessionRegistry.getByContactId(member.userJid())
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(notification));
        }

        // 5. Store rotation event for offline delivery
        storeRotationEvent(groupId, newGeneration, reason,
                currentMembers, notification);

        logger.info("Sender keys rotated: groupId=" + groupId
                + " generation=" + newGeneration
                + " reason=" + reason
                + " members=" + currentMembers.size());
    }

    private int incrementGeneration(String groupId,
                                      RotationReason reason,
                                      String triggerUserJid) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // Upsert generation row
            String sql = """
                INSERT INTO group_sender_key_generations (
                    group_id, generation, rotated_at, reason, trigger_jid
                ) VALUES (?, 1, NOW(), ?, ?)
                ON DUPLICATE KEY UPDATE
                    generation = generation + 1,
                    rotated_at = NOW(),
                    reason = VALUES(reason),
                    trigger_jid = VALUES(trigger_jid)
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, reason.name());
                stmt.setString(3, triggerUserJid);
                stmt.executeUpdate();
            }

            int newGen;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT generation FROM group_sender_key_generations " +
                    "WHERE group_id = ?")) {
                stmt.setString(1, groupId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) return -1;
                    newGen = rs.getInt("generation");
                }
            }

            conn.commit();
            return newGen;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) {}
            logger.severe("incrementGeneration error: " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private List<MemberKeyInfo> fetchCurrentMembers(String groupId) {
        String sql = """
            SELECT gm.user_jid,
                   uk.identity_key,
                   uk.registration_id,
                   uk.device_id
            FROM group_members gm
            INNER JOIN user_keys uk
                ON uk.user_id = gm.user_id
            WHERE gm.group_id = ?
              AND gm.left_at IS NULL
              AND gm.affiliation != 'outcast'
            """;

        List<MemberKeyInfo> result = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new MemberKeyInfo(
                            rs.getString("user_jid"),
                            rs.getString("identity_key"),
                            rs.getInt("registration_id"),
                            rs.getString("device_id")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("fetchCurrentMembers error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Builds the rotation notification stanza.
     *
     * Includes:
     *  - New generation number
     *  - Rotation reason
     *  - List of CURRENT members (so clients know who gets the new key)
     *  - Each member's identity key (for SenderKeyDistributionMessage encryption)
     */
    private String buildRotationStanza(String groupId, int generation,
                                         RotationReason reason,
                                         List<MemberKeyInfo> members) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<message type='headline'>" +
            "<sender-key-rotation xmlns='%s' group_id='%s'" +
            " generation='%d' reason='%s'>",
            SIGNAL_NS, escapeXml(groupId), generation, reason.name()
        ));

        sb.append("<members>");
        for (MemberKeyInfo m : members) {
            sb.append(String.format(
                "<member jid='%s' device_id='%s'" +
                " registration_id='%d' identity_key='%s'/>",
                escapeXml(m.userJid()),
                escapeXml(m.deviceId() != null ? m.deviceId() : ""),
                m.registrationId(),
                escapeXml(m.identityKey() != null ? m.identityKey() : "")
            ));
        }
        sb.append("</members>");
        sb.append("</sender-key-rotation></message>");

        return sb.toString();
    }

    /**
     * Stores the rotation event for offline delivery.
     * When an offline member comes back online, they'll receive
     * all rotation events they missed.
     */
    private void storeRotationEvent(String groupId, int generation,
                                      RotationReason reason,
                                      List<MemberKeyInfo> members,
                                      String stanza) {
        String sql = """
            INSERT INTO group_sender_key_events (
                group_id, generation, reason, member_list, stanza_xml,
                created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL 30 DAY)
            """;

        StringBuilder memberList = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) memberList.append(",");
            memberList.append(members.get(i).userJid());
        }

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            stmt.setInt(2, generation);
            stmt.setString(3, reason.name());
            stmt.setString(4, memberList.toString());
            stmt.setString(5, stanza);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("storeRotationEvent error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Catch-up for returning members
    // =========================================================================

    /**
     * When an offline member comes online, deliver all missed rotation events
     * for groups they're currently a member of.
     */
    public void deliverMissedRotations(Session session, String userJid) {
        executor.execute(() -> {
            String sql = """
                SELECT DISTINCT ev.stanza_xml
                FROM group_sender_key_events ev
                INNER JOIN group_members gm
                    ON gm.group_id = ev.group_id
                    AND gm.user_jid = ?
                    AND gm.left_at IS NULL
                    AND gm.affiliation != 'outcast'
                WHERE ev.created_at > gm.joined_at
                  AND FIND_IN_SET(?, ev.member_list) > 0
                  AND ev.expires_at > NOW()
                ORDER BY ev.created_at ASC
                """;

            try (Connection conn = pool.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, userJid);
                stmt.setString(2, userJid);

                try (ResultSet rs = stmt.executeQuery()) {
                    int delivered = 0;
                    while (rs.next()) {
                        session.writeXML(rs.getString("stanza_xml"));
                        delivered++;
                    }
                    if (delivered > 0) {
                        logger.info("Delivered " + delivered
                                + " missed rotation events to " + userJid);
                    }
                }

            } catch (SQLException e) {
                logger.warning("deliverMissedRotations error: " + e.getMessage());
            }
        });
    }

    /**
     * Returns the current sender key generation for a group.
     * Used by clients to verify they have the latest key.
     */
    public int getCurrentGeneration(String groupId) {
        String sql = """
            SELECT generation FROM group_sender_key_generations
            WHERE group_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("generation") : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Types
    // =========================================================================

    public enum RotationReason {
        MEMBER_ADDED,
        MEMBER_REMOVED,
        MEMBER_BANNED,
        AFFILIATION_CHANGED,
        SECURITY_CHANGE,
        PERIODIC_ROTATION   // Optional: monthly forced rotation
    }

    private record MemberKeyInfo(
            String userJid,
            String identityKey,
            int registrationId,
            String deviceId
    ) {}
}