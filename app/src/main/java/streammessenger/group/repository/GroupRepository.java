package streammessenger.group.repository;

import com.github.f4b6a3.ulid.UlidCreator;

import streammessenger.db.ConnectionPool;
import streammessenger.group.handler.GroupStanzaHandler;
import streammessenger.group.model.*;
import streammessenger.muc.model.GroupEventType;

import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persistence layer for groups.
 * <p>
 * Key design decisions:
 *  - Every state-changing operation increments groups.state_version
 *  - Every state-changing operation inserts a row into group_state_events
 *  - Both happen in the same transaction
 *  - Clients use state_version for incremental sync
 */
public final class GroupRepository {
    private final SecureRandom secureRandom = new SecureRandom();

    private static final Logger logger =
            Logger.getLogger(GroupRepository.class.getName());

    private final ConnectionPool pool;
    private final String groupDomain;

    public GroupRepository(ConnectionPool pool, String groupDomain) {
        this.pool        = pool;
        this.groupDomain = groupDomain;
    }

    // =========================================================================
    // Create
    // =========================================================================

    private String generateGroupIdCompat(){
        byte[] bytes = new byte[4];
        secureRandom.nextBytes(bytes);

        StringBuilder sb = new StringBuilder("gr_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        // e.g. "gr_7f3a9b2c"

        return sb.toString();
    }
    /**
     * Creates a new group.
     * <p>
     * Transaction:
     *   1. INSERT group (state_version = 1)
     *   2. INSERT default settings
     *   3. INSERT creator as member with is_owner = true
     *   4. INSERT group_created event at version 1
     * <p>
     * Returns the created group.
     */
    public Group create(String name, String description,
                         String creatorUserId, String creatorJid,
                         GroupVisibility visibility, int maxMembers) {

        String groupId = generateGroupIdCompat();
        String jid     = groupId + "@" + groupDomain;

        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // 1. Insert group
            String groupSql = """
                INSERT INTO `groups` (
                    group_id, jid, name, description,
                    creator_user_id, visibility,
                    max_members, member_count, state_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
                """;
            try (PreparedStatement stmt = conn.prepareStatement(groupSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, jid);
                stmt.setString(3, name);
                stmt.setString(4, description);
                stmt.setString(5, creatorUserId);
                stmt.setString(6, visibility.xmlValue());
                stmt.setInt(7, maxMembers);
                stmt.executeUpdate();
            }

            // 2. Default settings
            String settingsSql = """
                INSERT INTO group_settings (
                    group_id, only_admins_can_send, only_admins_can_edit_info,
                    only_admins_can_add, disappearing_seconds, approval_required
                ) VALUES (?, FALSE, TRUE, FALSE, 0, FALSE)
                """;
            try (PreparedStatement stmt = conn.prepareStatement(settingsSql)) {
                stmt.setString(1, groupId);
                stmt.executeUpdate();
            }

            // 3. Add creator as owner
            String memberSql = """
                INSERT INTO group_members (
                    group_id, user_id, user_jid,
                    is_admin, is_owner, joined_at
                ) VALUES (?, ?, ?, TRUE, TRUE, CURRENT_TIMESTAMP(6))
                """;
            try (PreparedStatement stmt = conn.prepareStatement(memberSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, creatorUserId);
                stmt.setString(3, creatorJid);
                stmt.executeUpdate();
            }

            // 4. Initial event
            String payloadJson = String.format(
                "{\"name\":\"%s\",\"creator\":\"%s\",\"visibility\":\"%s\"}",
                escapeJson(name), creatorUserId, visibility.xmlValue()
            );

            insertEvent(conn, groupId, 1, "group_created",
                    creatorUserId, null, payloadJson);

            conn.commit();

            logger.info("Group created: groupId=" + groupId
                    + " creator=" + creatorUserId);

            return new Group(
                    groupId, jid, name, description, null,
                    creatorUserId, visibility, maxMembers, 1, 1,
                    Instant.now()
            );

        } catch (SQLException e) {
            rollback(conn);
            logger.severe("create error: " + e.getMessage());
            throw new RuntimeException("Failed to create group", e);
        } finally {
            close(conn);
        }
    }


    /**
     * Stores an encrypted offline message.
     * <p>
     * The server stores CIPHERTEXT only.
     * It never sees the plaintext content.
     *
     * @param fromJid          Sender's full JID
     * @param toJid            Recipient's bare JID
     * @param messageId        Client-generated UUID for this message
     * @param messageType      text | image | video | audio | file | location
     * @param encryptedContent Base64 AES-256-GCM ciphertext
     * @param iv               Base64 12-byte IV
     * @param mediaStorageKey  Object storage path (null for text messages)
     * @param mimeType         MIME type hint (null for text messages)
     * @param fileSizeBytes    File size in bytes (0 for text messages)
     * @param replyToId        UUID of message being replied to (null if none)
     */
    @Deprecated
    private boolean storeEncryptedMessageEventModelOld(String fromJid,
                                                   String toJid,
                                                   String messageId,
                                                   String messageType,
                                                   String encryptedContent,
                                                   String iv,
                                                   String mediaStorageKey,
                                                   String encryptedMetadata,
                                                   String mimeType,
                                                   long fileSizeBytes,
                                                   String replyToId) {
        String eventId = UlidCreator.getMonotonicUlid().toString().toUpperCase();
        String sql = """
                INSERT INTO events (
                    event_id,
                    event_category,
                    event_type,
                    group_id,
                    sender_id,
                    recipient_id,
                    sender_jid,
                    group_version,
                    epoch,
                    iv,
                    encrypted_content,
                    encrypted_metadata,
                    reply_to_event_id,
                    media_storage_key,
                    mime_type,
                    file_size_bytes,
                    created_at,
                    expires_at
                )
                SELECT
                    ?,
                    f.user_id,
                    t.user_id,
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    'pending',
                    true,
                    NOW(),
                    NOW() + INTERVAL 30 DAY
                FROM users f
                JOIN users t
                WHERE f.user_id = ?
                  AND t.user_id = ?
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.setString(2, messageType);
            stmt.setString(3, encryptedContent);
            stmt.setString(4, iv);
            stmt.setString(5, mediaStorageKey);
            stmt.setString(6, encryptedMetadata);
            stmt.setString(7, mimeType);
            stmt.setLong(8, fileSizeBytes);
            stmt.setString(9, replyToId);
            stmt.setString(10, fromJid);
            stmt.setString(11, toJid);

            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("storeEncryptedMessage error: " + e.getMessage());
            return false;
        }
    }


    public boolean storeEncryptedMessageEventModel(String fromJid,
                                                   String toJid,
                                                   String messageId,
                                                   String messageType,
                                                   String encryptedContent,
                                                   String replyToId,
                                                   int groupVersion,
                                                   int epoch) {

        // 1. Generate the monotonic sortable ID anchor
        String eventId = UlidCreator.getMonotonicUlid().toString().toUpperCase();

        // 2. Exactly matching column count (17 structural items)
        String sql = """
            INSERT INTO events (
                event_id,
                event_category,
                event_type,
                group_id,
                sender_id,
                sender_jid,
                group_version,
                epoch,
                encrypted_content,
                reply_to_event_id,
                created_at,
                expires_at,
                event_ref_id
            ) VALUES (?, 'groupchat', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), NOW() + INTERVAL 30 DAY, ?)
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Explicit structural mapping to prevent index confusion
            stmt.setString(1, eventId);                 // Primary Key
            stmt.setString(2, messageType);             // e.g., 'text', 'image'
            stmt.setString(3, toJid);                   // group_id (the target destination)
            stmt.setString(4, fromJid);                 // sender_id
            stmt.setString(5, fromJid + "@server");     // sender_jid layout
            stmt.setInt(6, groupVersion);               // Crucial state guardrail
            stmt.setInt(7, epoch);                      // E2EE cryptographic tracking boundaries
            stmt.setString(9, encryptedContent);        // Ciphertext payload
            stmt.setString(11, replyToId);              // Parent event identifier reference


            stmt.setString(12, messageId); // For deletion e.t.c

            int rows = stmt.executeUpdate();

            // Only call commit manually if your pool connection defaults to autoCommit = false
            if (!conn.getAutoCommit()) {
                conn.commit();
            }

            return rows > 0;

        } catch (SQLException e) {
            logger.severe("storeEncryptedMessageEventModel failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fetches a single, perfectly sorted timeline delta for a user across all
     * 1-to-1 chats and authorized group membership windows.
     * * @param userId The ID of the connecting user (e.g., 'alice_id')
     * @param clientLastSeenUlid The highest ULID string the client has stored locally
     * @param limit The maximum number of timeline events to return in a single page
     */
    public List<UnifiedTimelineItem> getUnifiedTimelineDelta(String userId, String clientLastSeenUlid, int limit) {
        List<UnifiedTimelineItem> timeline = new ArrayList<>();

        String sql = """
            SELECT
                e.event_id,
                e.event_ref_id,
                e.event_category,
                e.event_type,
                e.group_id,
                e.sender_id,
                e.sender_jid,
                e.group_version,
                e.epoch,
                e.iv,
                e.encrypted_content,
                e.encrypted_metadata,
                e.reply_to_event_id,
                e.media_storage_key,
                e.mime_type,
                e.file_size_bytes,
                e.created_at
            FROM events e
            LEFT JOIN group_member m
              ON e.group_id = m.group_id AND m.user_id = ?
            WHERE
                -- Branch A: Direct private messages intended for this specific client
                (e.event_category = 'chat' AND e.recipient_id = ? AND e.event_id > ?)
                OR
                -- Branch B: Group messages and events bound by historical residency windows
                (e.event_category IN ('groupchat', 'system')
                 AND m.user_id IS NOT NULL
                 AND e.event_id > ?
                 AND e.sender_id != ?
                 AND e.event_id >= m.joined_at_id
                 AND (m.left_at_id IS NULL OR e.event_id <= m.left_at_id))
            ORDER BY e.event_id ASC
            LIMIT ?;
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Map the parameters cleanly to match the query indexes
            stmt.setString(1, userId);             // For the LEFT JOIN evaluation
            stmt.setString(2, userId);             // Branch A: recipient_id
            stmt.setString(3, clientLastSeenUlid); // Branch A: anchor
            stmt.setString(4, clientLastSeenUlid); // Branch B: anchor
            stmt.setString(5, userId); // Exclude the message that the current session sent
            stmt.setInt(6, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    timeline.add(mapRowToTimelineItem(rs));
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to pull unified timeline delta for user " + userId + ": " + e.getMessage());
        }

        return timeline;
    }

    private UnifiedTimelineItem mapRowToTimelineItem(ResultSet rs) throws SQLException {
        return new UnifiedTimelineItem(
                rs.getString("event_id"),
                rs.getString("event_ref_id"),
                rs.getString("event_category"),
                rs.getString("event_type"),
                rs.getString("group_id"),
                rs.getString("sender_id"),
                rs.getString("sender_jid"),
                rs.getInt("group_version"),
                rs.getInt("epoch"),
                rs.getString("encrypted_content"),
                rs.getString("reply_to_event_id"),
                rs.getTimestamp("created_at")
        );
    }

    /**
     * Represents a perfectly ordered, polymorphic timeline element
     * pulled from the unified 'events' table.
     */
    public record UnifiedTimelineItem(
            String id,                  // The sortable ULID string
            String eventRefId,          // The UUID for the content
            String eventCategory,       // 'chat', 'groupchat', 'system'
            String eventType,           // 'text', 'image', 'member_left', etc.
            String groupId,             // Null for 1-to-1 chats
            String senderId,            // The raw user ID of the sender/actor
            String senderJid,           // Full XMPP address identifier
            int groupVersion,           // Config sequence state version
            int epoch,                  // Cryptographic ratchet fence boundary
            String encryptedContent,    // Ciphertext payload or event JSON data
            String replyToEventId,      // Self-referencing structural link
            Timestamp createdAt         // Precise database capture stamp
    ) {

        // Quick helper tools to make your streaming routing conditions highly readable
        public boolean isDirectMessage() {
            return "chat".equals(eventCategory);
        }

        public boolean isGroupMessage() {
            return "groupchat".equals(eventCategory);
        }

        public boolean isGroupEvent() {
            return "system".equals(eventCategory);
        }
    }
    // =========================================================================
    // Read
    // =========================================================================

    public Group get(String groupId) {
        String sql = """
            SELECT group_id, jid, name, description, avatar_url,
                   creator_user_id, visibility, max_members,
                   member_count, state_version, created_at, deleted_at
            FROM `groups`
            WHERE group_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                if (rs.getTimestamp("deleted_at") != null) return null;
                return mapGroup(rs);
            }
        } catch (SQLException e) {
            logger.severe("get error: " + e.getMessage());
            return null;
        }
    }

    public GroupSettings getSettings(String groupId) {
        String sql = """
            SELECT only_admins_can_send, only_admins_can_edit_info,
                   only_admins_can_add, disappearing_seconds, approval_required
            FROM group_settings WHERE group_id = ?
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return GroupSettings.defaults();
                return new GroupSettings(
                        rs.getBoolean("only_admins_can_send"),
                        rs.getBoolean("only_admins_can_edit_info"),
                        rs.getBoolean("only_admins_can_add"),
                        rs.getInt("disappearing_seconds"),
                        rs.getBoolean("approval_required")
                );
            }
        } catch (SQLException e) {
            return GroupSettings.defaults();
        }
    }

    public GroupMember getMember(String groupId, String userId) {
        String sql = """
            SELECT gm.user_id, gm.user_jid, u.phone_number, u.avatar_url, u.display_status, u.display_name,
                   gm.is_admin, gm.is_owner, gm.muted_until,
                   gm.joined_at, gm.added_by_user_id
            FROM group_members gm
            INNER JOIN users u ON u.user_id = gm.user_id
            WHERE gm.group_id = ? AND gm.user_id = ? AND gm.left_at IS NULL
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapMember(rs);
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public List<GroupMember> listMembers(String groupId) {
        String sql = """
            SELECT gm.user_id, gm.user_jid, u.phone_number, u.avatar_url, u.display_status, u.display_name,
                   gm.is_admin, gm.is_owner, gm.muted_until,
                   gm.joined_at, gm.added_by_user_id
            FROM group_members gm
            INNER JOIN users u ON u.user_id = gm.user_id
            WHERE gm.group_id = ? AND gm.left_at IS NULL
            ORDER BY gm.is_owner DESC, gm.is_admin DESC, gm.joined_at ASC
            """;
        List<GroupMember> members = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) members.add(mapMember(rs));
            }
        } catch (SQLException e) {
            logger.severe("listMembers error: " + e.getMessage());
        }
        return members;
    }

    /**
     * Returns groups a user is in, with optional version filter for sync.
     * If clientVersions = null: returns all groups with current version.
     * If clientVersions provided: returns only groups where version differs.
     */
    public List<Group> listUserGroups(String userId) {
        String sql = """
            SELECT g.group_id, g.jid, g.name, g.description, g.avatar_url,
                   g.creator_user_id, g.visibility, g.max_members,
                   g.member_count, g.state_version, g.created_at, g.deleted_at
            FROM `groups` g
            INNER JOIN group_members gm
                ON gm.group_id = g.group_id
                AND gm.user_id = ?
                AND gm.left_at IS NULL
            WHERE g.deleted_at IS NULL
            ORDER BY g.updated_at DESC
            """;
        List<Group> groups = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) groups.add(mapGroup(rs));
            }
        } catch (SQLException e) {
            logger.severe("listUserGroups error: " + e.getMessage());
        }
        return groups;
    }

    /**
     * Returns all JIDs of active members - used for fanout.
     */
    public List<String> listMemberJids(String groupId) {
        String sql = """
            SELECT user_jid FROM group_members
            WHERE group_id = ? AND left_at IS NULL
            """;
        List<String> jids = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) jids.add(rs.getString("user_jid"));
            }
        } catch (SQLException e) {
            logger.severe("listMemberJids error: " + e.getMessage());
        }
        return jids;
    }

    /**
     * Quick check: is this user a member?
     * Used for authorization before any group operation.
     */
    public boolean isMember(String groupId, String userId) {
        String sql = """
            SELECT 1 FROM group_members
            WHERE group_id = ? AND user_id = ? AND left_at IS NULL
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // =========================================================================
    // Mutations
    // =========================================================================

    /**
     * Adds a member to the group.
     * <p>
     * Transaction:
     *   1. INSERT member (handles re-adding via ON DUPLICATE)
     *   2. UPDATE groups.member_count and increment state_version
     *   3. INSERT member_added event
     * <p>
     * Returns the new state_version.
     */
    public long addMember(String groupId, String newUserId, String newUserJid,
                           String addedByUserId, boolean asAdmin) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // 1. Insert/reactivate member
            String memberSql = """
                INSERT INTO group_members (
                    group_id, user_id, user_jid,
                    is_admin, is_owner, added_by_user_id, joined_at
                ) VALUES (?, ?, ?, ?, FALSE, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    is_admin           = VALUES(is_admin),
                    added_by_user_id   = VALUES(added_by_user_id),
                    joined_at          = CURRENT_TIMESTAMP(6),
                    left_at            = NULL,
                    removed_by_user_id = NULL
                """;
            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(memberSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, newUserId);
                stmt.setString(3, newUserJid);
                stmt.setBoolean(4, asAdmin);
                stmt.setString(5, addedByUserId);
                rows = stmt.executeUpdate();
            }

            boolean isFirstTime = (rows == 1);

            // 2. Increment counters and version
            long newVersion = incrementVersion(conn, groupId);
            if (isFirstTime) {
                bumpMemberCount(conn, groupId, 1);
            }

            // 3. Event
            String payload = String.format(
                "{\"user_id\":\"%s\",\"user_jid\":\"%s\",\"is_admin\":%b}",
                newUserId, escapeJson(newUserJid), asAdmin
            );
            insertEvent(conn, groupId, newVersion, "member_added",
                    addedByUserId, newUserId, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            logger.severe("addMember error: " + e.getMessage());
            throw new RuntimeException("Failed to add member", e);
        } finally {
            close(conn);
        }
    }

    // =========================================================================
    // Join via invite link (no actor - self-initiated)
    // =========================================================================

    /**
     * Adds a member via invite link (no admin involved).
     * <p>
     * Different from addMember() because:
     *   - actor_user_id is NULL (no one added them)
     *   - added_by_user_id is NULL
     *   - Event type is "member_joined_via_link"
     *   - link_token is tracked in payload for audit
     * <p>
     * Transaction:
     *   1. Atomically check link is valid + claim it
     *   2. INSERT member
     *   3. UPDATE counters + state_version
     *   4. INSERT member_joined_via_link event
     */
    public LinkJoinResult joinViaLink(String linkToken,
                                      String newUserId,
                                      String newUserJid) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // 1. Atomically validate link AND increment usage
            String groupId = null;
            String createdByUserId = null;

            String validateSql = """
                UPDATE group_invite_links
                SET use_count = use_count + 1
                WHERE link_token = ?
                  AND revoked = FALSE
                """;
            int updated;
            try (PreparedStatement stmt = conn.prepareStatement(validateSql)) {
                stmt.setString(1, linkToken);
                updated = stmt.executeUpdate();
            }

            if (updated == 0) {
                conn.rollback();
                return null; // Link doesn't exist or was revoked
            }

            // Read the group_id and creator
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT group_id, created_by_user_id " +
                            "FROM group_invite_links WHERE link_token = ?")) {
                stmt.setString(1, linkToken);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        groupId = rs.getString("group_id");
                        createdByUserId = rs.getString("created_by_user_id");
                    }
                }
            }

            if (groupId == null) {
                conn.rollback();
                return null;
            }

            // 2. Check user is not already a member or banned
            String checkSql = """
                SELECT 1 FROM group_members
                WHERE group_id = ? AND user_id = ? AND left_at IS NULL
                """;
            try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, newUserId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return LinkJoinResult.alreadyMember(groupId);
                    }
                }
            }

            // 3. Check group capacity
            int memberCount = 0, maxMembers = 0;
            boolean isDeleted = false;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT member_count, max_members, deleted_at " +
                            "FROM `groups` WHERE group_id = ?")) {
                stmt.setString(1, groupId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        memberCount = rs.getInt("member_count");
                        maxMembers = rs.getInt("max_members");
                        isDeleted = rs.getTimestamp("deleted_at") != null;
                    }
                }
            }

            if (isDeleted) {
                conn.rollback();
                return null;
            }
            if (memberCount >= maxMembers) {
                conn.rollback();
                return LinkJoinResult.groupFull(groupId);
            }

            // 4. Add member (reactivate if previously left)
            String memberSql = """
                INSERT INTO group_members (
                    group_id, user_id, user_jid,
                    is_admin, is_owner, added_by_user_id, joined_at
                ) VALUES (?, ?, ?, FALSE, FALSE, NULL, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    is_admin           = FALSE,
                    added_by_user_id   = NULL,
                    joined_at          = CURRENT_TIMESTAMP(6),
                    left_at            = NULL,
                    removed_by_user_id = NULL
                """;
            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(memberSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, newUserId);
                stmt.setString(3, newUserJid);
                rows = stmt.executeUpdate();
            }

            boolean isFirstTime = (rows == 1);

            // 5. Increment counters and version
            long newVersion = incrementVersion(conn, groupId);
            if (isFirstTime) {
                bumpMemberCount(conn, groupId, 1);
            }

            // 6. Event - note actor_user_id is NULL because no one added them
            //         target_user_id is the new user themselves
            String payload = String.format(
                    "{\"user_id\":\"%s\",\"user_jid\":\"%s\"," +
                            "\"link_token\":\"%s\",\"link_created_by\":\"%s\"}",
                    newUserId,
                    escapeJson(newUserJid),
                    escapeJson(linkToken),
                    createdByUserId
            );
            insertEvent(conn, groupId, newVersion,
                    GroupEventType.MEMBER_JOINED_VIA_LINK.name(),
                    null,  // actor is null - they joined themselves
                    newUserId,
                    payload);

            conn.commit();

            logger.info("Member joined via link: groupId=" + groupId
                    + " user=" + newUserId + " token=" + linkToken);

            return LinkJoinResult.success(groupId, newVersion);

        } catch (SQLException e) {
            rollback(conn);
            logger.severe("joinViaLink error: " + e.getMessage());
            throw new RuntimeException("Failed to join via link", e);
        } finally {
            close(conn);
        }
    }

    /**
     * Result of a link join attempt.
     * Distinguishes between success, already-member, group-full, invalid-link.
     */
    public record LinkJoinResult(
            Status status,
            String groupId,
            long newVersion
    ) {
        public enum Status {
            SUCCESS,
            ALREADY_MEMBER,
            GROUP_FULL,
            INVALID_LINK
        }

        public static LinkJoinResult success(String groupId, long version) {
            return new LinkJoinResult(Status.SUCCESS, groupId, version);
        }

        public static LinkJoinResult alreadyMember(String groupId) {
            return new LinkJoinResult(Status.ALREADY_MEMBER, groupId, 0);
        }

        public static LinkJoinResult groupFull(String groupId) {
            return new LinkJoinResult(Status.GROUP_FULL, groupId, 0);
        }

        public boolean isSuccess() { return status == Status.SUCCESS; }
    }


    // =========================================================================
    // Invite links
    // =========================================================================

    public InviteLink createInviteLink(String groupId, String creatorUserId) {
        String token = generateLinkToken();
        String sql = """
            INSERT INTO group_invite_links (
                link_token, group_id, created_by_user_id
            ) VALUES (?, ?, ?)
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, token);
            stmt.setString(2, groupId);
            stmt.setString(3, creatorUserId);
            stmt.executeUpdate();
            conn.commit();

            return new InviteLink(token, groupId, creatorUserId,
                    java.time.Instant.now(), false);

        } catch (SQLException e) {
            logger.severe("createInviteLink error: " + e.getMessage());
            throw new RuntimeException("Failed to create link", e);
        }
    }

    public void revokeInviteLink(String linkToken, String actorUserId) {
        String sql = """
            UPDATE group_invite_links
            SET revoked = TRUE,
                revoked_at = CURRENT_TIMESTAMP(6),
                revoked_by_user_id = ?
            WHERE link_token = ? AND revoked = FALSE
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, actorUserId);
            stmt.setString(2, linkToken);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            logger.severe("revokeInviteLink error: " + e.getMessage());
        }
    }

    public List<InviteLink> listInviteLinks(String groupId) {
        String sql = """
            SELECT link_token, group_id, created_by_user_id,
                   use_count, revoked, created_at
            FROM group_invite_links
            WHERE group_id = ? AND revoked = FALSE
            ORDER BY created_at DESC
            """;
        List<InviteLink> links = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    links.add(new InviteLink(
                            rs.getString("link_token"),
                            rs.getString("group_id"),
                            rs.getString("created_by_user_id"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getBoolean("revoked")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.severe("listInviteLinks error: " + e.getMessage());
        }
        return links;
    }

    public Optional<InviteLink> getInviteLink(String inviteLink){
        String sql = """
            SELECT link_token, group_id, created_by_user_id,
                   use_count, revoked, created_at
            FROM group_invite_links
            WHERE link_token = ?
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, inviteLink);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new InviteLink(
                            rs.getString("link_token"),
                            rs.getString("group_id"),
                            rs.getString("created_by_user_id"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getBoolean("revoked")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.severe("listInviteLinks error: " + e.getMessage());
        }
        return Optional.empty();
    }

    private String generateLinkToken() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder()
                .withoutPadding().encodeToString(bytes);
    }

    public record InviteLink(
            String token,
            String groupId,
            String createdByUserId,
            java.time.Instant createdAt,
            boolean revoke
    ) {}

    public long removeMember(String groupId, String targetUserId,
                              String actorUserId, boolean voluntary) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_members
                SET left_at = CURRENT_TIMESTAMP(6),
                    removed_by_user_id = ?
                WHERE group_id = ? AND user_id = ? AND left_at IS NULL
                """;
            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, voluntary ? null : actorUserId);
                stmt.setString(2, groupId);
                stmt.setString(3, targetUserId);
                rows = stmt.executeUpdate();
            }

            if (rows == 0) {
                conn.rollback();
                return 0;
            }

            long newVersion = incrementVersion(conn, groupId);
            bumpMemberCount(conn, groupId, -1);

            String eventType = voluntary ? "member_left" : "member_removed";
            String payload = String.format(
                "{\"user_id\":\"%s\"}", targetUserId
            );
            insertEvent(conn, groupId, newVersion, eventType,
                    actorUserId, targetUserId, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            throw new RuntimeException("Failed to remove member", e);
        } finally {
            close(conn);
        }
    }

    public long grantAdmin(String groupId, String targetUserId,
                            String actorUserId) {
        return changeAdminStatus(groupId, targetUserId, actorUserId,
                true, "admin_granted");
    }

    public long revokeAdmin(String groupId, String targetUserId,
                             String actorUserId) {
        return changeAdminStatus(groupId, targetUserId, actorUserId,
                false, "admin_revoked");
    }

    private long changeAdminStatus(String groupId, String targetUserId,
                                     String actorUserId, boolean isAdmin,
                                     String eventType) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_members
                SET is_admin = ?
                WHERE group_id = ? AND user_id = ? AND left_at IS NULL
                AND is_owner = FALSE
                """;
            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, isAdmin);
                stmt.setString(2, groupId);
                stmt.setString(3, targetUserId);
                rows = stmt.executeUpdate();
            }

            if (rows == 0) {
                conn.rollback();
                return 0;
            }

            long newVersion = incrementVersion(conn, groupId);

            String payload = String.format(
                "{\"user_id\":\"%s\",\"is_admin\":%b}",
                targetUserId, isAdmin
            );
            insertEvent(conn, groupId, newVersion, eventType,
                    actorUserId, targetUserId, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            throw new RuntimeException("Failed to change admin status", e);
        } finally {
            close(conn);
        }
    }

    public long transferOwnership(String groupId, String oldOwnerUserId,
                                    String newOwnerUserId) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // Old owner becomes admin
            String demoteSql = """
                UPDATE group_members
                SET is_owner = FALSE, is_admin = TRUE
                WHERE group_id = ? AND user_id = ?
                  AND is_owner = TRUE AND left_at IS NULL
                """;
            int r1;
            try (PreparedStatement stmt = conn.prepareStatement(demoteSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, oldOwnerUserId);
                r1 = stmt.executeUpdate();
            }

            // New owner gets owner + admin status
            String promoteSql = """
                UPDATE group_members
                SET is_owner = TRUE, is_admin = TRUE
                WHERE group_id = ? AND user_id = ? AND left_at IS NULL
                """;
            int r2;
            try (PreparedStatement stmt = conn.prepareStatement(promoteSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, newOwnerUserId);
                r2 = stmt.executeUpdate();
            }

            if (r1 == 0 || r2 == 0) {
                conn.rollback();
                return 0;
            }

            long newVersion = incrementVersion(conn, groupId);

            String payload = String.format(
                "{\"from\":\"%s\",\"to\":\"%s\"}",
                oldOwnerUserId, newOwnerUserId
            );
            insertEvent(conn, groupId, newVersion, "owner_transferred",
                    oldOwnerUserId, newOwnerUserId, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            throw new RuntimeException("Failed to transfer ownership", e);
        } finally {
            close(conn);
        }
    }

    public long updateMetadata(String groupId, String actorUserId,
                                String name, String description,
                                String avatarUrl) {

        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            StringBuilder sql = new StringBuilder("UPDATE `groups` SET ");
            List<Object> params = new ArrayList<>();

            if (name != null) {
                sql.append("name = ?, ");
                params.add(name);
            }
            if (description != null) {
                sql.append("description = ?, ");
                params.add(description);
            }
            if (avatarUrl != null) {
                sql.append("avatar_url = ?, ");
                params.add(avatarUrl);
            }

            if (params.isEmpty()) {
                conn.rollback();
                return 0;
            }

            sql.append("updated_at = CURRENT_TIMESTAMP(6) WHERE group_id = ?");
            params.add(groupId);

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                stmt.executeUpdate();
            }

            long newVersion = incrementVersion(conn, groupId);


            String payload = String.format(
                "{%s%s%s}",
                name != null ? "\"name\":\"" + escapeJson(name) + "\"," : "",
                description != null ? "\"description\":\""
                        + escapeJson(description) + "\"," : "",
                avatarUrl != null ? "\"avatar_url\":\""
                        + escapeJson(avatarUrl) + "\"" : ""
            ).replace(",}", "}");

            String eventType = "metadata_changed";

            if(name != null) eventType = "name_changed";
            if(description != null) eventType = "description_changed";
            if(avatarUrl != null) eventType = "avatar_changed";
            insertEvent(conn, groupId, newVersion, eventType,
                    actorUserId, null, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            throw new RuntimeException("Failed to update metadata", e);
        } finally {
            close(conn);
        }
    }

    public long updateSettings(String groupId, String actorUserId,
                                GroupSettings settings) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_settings SET
                    only_admins_can_send      = ?,
                    only_admins_can_edit_info = ?,
                    only_admins_can_add       = ?,
                    disappearing_seconds      = ?,
                    approval_required         = ?,
                    updated_by_user_id        = ?
                WHERE group_id = ?
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, settings.onlyAdminsCanSend());
                stmt.setBoolean(2, settings.onlyAdminsCanEditInfo());
                stmt.setBoolean(3, settings.onlyAdminsCanAdd());
                stmt.setInt(4, settings.disappearingSeconds());
                stmt.setBoolean(5, settings.approvalRequired());
                stmt.setString(6, actorUserId);
                stmt.setString(7, groupId);
                stmt.executeUpdate();
            }

            long newVersion = incrementVersion(conn, groupId);

            String payload = String.format(
                "{\"only_admins_send\":%b,\"only_admins_edit\":%b," +
                "\"only_admins_add\":%b,\"disappearing\":%d," +
                "\"approval\":%b}",
                settings.onlyAdminsCanSend(),
                settings.onlyAdminsCanEditInfo(),
                settings.onlyAdminsCanAdd(),
                settings.disappearingSeconds(),
                settings.approvalRequired()
            );
            insertEvent(conn, groupId, newVersion, "settings_changed",
                    actorUserId, null, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            throw new RuntimeException("Failed to update settings", e);
        } finally {
            close(conn);
        }
    }

    public long updateSettingsDelta(String groupId, String actorUserId, GroupStanzaHandler.ParsedGroupIQ incoming) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // 1. Fetch current database states using row-level locking to avoid race conditions
            GroupSettings current = fetchGroupSettingsForUpdate(conn, groupId);
            if (current == null) {
                throw new RuntimeException("Group settings row missing");
            }

            // 2. Build the merged state and track exactly what changed
            boolean onlyAdminsSend = incoming.onlyAdminsCanSend() != null ? incoming.onlyAdminsCanSend() : current.onlyAdminsCanSend();
            boolean onlyAdminsEdit = incoming.onlyAdminsCanEditInfo() != null ? incoming.onlyAdminsCanEditInfo() : current.onlyAdminsCanEditInfo();
            boolean onlyAdminsAdd  = incoming.onlyAdminsCanAdd() != null  ? incoming.onlyAdminsCanAdd()  : current.onlyAdminsCanAdd();
            int disappearingSecs   = incoming.disappearingSeconds() != null ? incoming.disappearingSeconds() : current.disappearingSeconds();
            boolean approvalReq    = incoming.approvalRequired() != null ? incoming.approvalRequired() : current.approvalRequired();

            // 3. Update the database record with the clean, merged dataset
            String sql = """
            UPDATE group_settings SET
                only_admins_can_send      = ?,
                only_admins_can_edit_info = ?,
                only_admins_can_add       = ?,
                disappearing_seconds      = ?,
                approval_required         = ?,
                updated_by_user_id        = ?
            WHERE group_id = ?
            """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, onlyAdminsSend);
                stmt.setBoolean(2, onlyAdminsEdit);
                stmt.setBoolean(3, onlyAdminsAdd);
                stmt.setInt(4, disappearingSecs);
                stmt.setBoolean(5, approvalReq);
                stmt.setString(6, actorUserId);
                stmt.setString(7, groupId);
                stmt.executeUpdate();
            }

            // 4. Sequence Version tracking increments natively
            long newVersion = incrementVersion(conn, groupId);

            // 5. Narrow down our historic audit logging to focus ONLY on the explicit mutation
            String eventType = "settings_changed";
            String eventPayload = "";

            if (incoming.onlyAdminsCanSend() != null) {
                eventType = "settings_only_admins_send";
                eventPayload = String.valueOf(onlyAdminsSend);
            } else if (incoming.onlyAdminsCanEditInfo() != null) {
                eventType = "settings_only_admins_meta";
                eventPayload = String.valueOf(onlyAdminsEdit);
            } else if (incoming.onlyAdminsCanAdd() != null) {
                eventType = "settings_only_admins_add";
                eventPayload = String.valueOf(onlyAdminsAdd);
            } else if (incoming.disappearingSeconds() != null) {
                eventType = "settings_disappearing_seconds";
                eventPayload = String.valueOf(disappearingSecs);
            } else if (incoming.approvalRequired() != null) {
                eventType = "settings_approval_required";
                eventPayload = String.valueOf(approvalReq);
            }

            // Save our precise, lightweight historical action row to the timeline log
            insertEvent(conn, groupId, newVersion, eventType, actorUserId, null, eventPayload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            throw new RuntimeException("Failed to patch group settings safely", e);
        } finally {
            close(conn);
        }
    }

    public long updateOnlyAdminCanEditInfo(String groupId, String actorUserId,
                               boolean state) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_settings SET
                    only_admins_can_edit_info = ?
                WHERE group_id = ?
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, state);
                stmt.setString(2, groupId);
                stmt.executeUpdate();
            }

            long newVersion = incrementVersion(conn, groupId);

            String payload = String.format(
                    "{\"only_admins_edit\":%b}",
                    state
            );
            insertEvent(conn, groupId, newVersion, "only_admins_edit",
                    actorUserId, null, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            logger.info("Failed to update settings: "+e.getMessage());
            throw new RuntimeException("Failed to update settings", e);
        } finally {
            close(conn);
        }
    }

    public long updateOnlyAdminCanSendMessage(String groupId, String actorUserId,
                                           boolean state) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_settings SET
                    only_admins_can_send = ?
                WHERE group_id = ?
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, state);
                stmt.setString(2, groupId);
                stmt.executeUpdate();
            }

            long newVersion = incrementVersion(conn, groupId);

            String payload = String.format(
                    "{\"only_admins_send\":%b}",
                    state
            );
            insertEvent(conn, groupId, newVersion, "only_admins_send",
                    actorUserId, null, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            logger.info("Failed to update settings: "+e.getMessage());
            throw new RuntimeException("Failed to update settings", e);
        } finally {
            close(conn);
        }
    }


    public long updateOnlyAdminCanAdd(String groupId, String actorUserId,
                                              boolean state) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_settings SET
                    only_admins_can_add = ?
                WHERE group_id = ?
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, state);
                stmt.setString(2, groupId);
                stmt.executeUpdate();
            }

            long newVersion = incrementVersion(conn, groupId);

            String payload = String.format(
                    "{\"only_admins_add\":%b}",
                    state
            );
            insertEvent(conn, groupId, newVersion, "only_admins_add",
                    actorUserId, null, payload);

            conn.commit();
            return newVersion;

        } catch (SQLException e) {
            rollback(conn);
            logger.info("Failed to update settings: "+e.getMessage());
            throw new RuntimeException("Failed to update settings", e);
        } finally {
            close(conn);
        }
    }
    private GroupSettings fetchGroupSettingsForUpdate(Connection conn, String groupId) throws SQLException {
        String sql = "SELECT only_admins_can_send, only_admins_can_edit_info, only_admins_can_add, disappearing_seconds, approval_required FROM group_settings WHERE group_id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet r = stmt.executeQuery()) {
                if (r.next()) {
                    return new GroupSettings(
                            r.getBoolean("only_admins_can_send"),
                            r.getBoolean("only_admins_can_edit_info"),
                            r.getBoolean("only_admins_can_add"),
                            r.getInt("disappearing_seconds"),
                            r.getBoolean("approval_required")
                    );
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Delta sync - the key WhatsApp pattern
    // =========================================================================

    /**
     * Returns all events for a group since a given version.
     * <p>
     * Used during delta sync:
     *   Client sends: "I have group X at version 5"
     *   Server returns: all events from version 6 onwards
     * <p>
     * Client applies events in order to update its local state.
     */
    public List<GroupStateEvent> getEventsSinceVersion(String groupId,
                                                        long sinceVersion,
                                                        int maxEvents) {
        String sql = """
            SELECT event_id, group_id, state_version, event_type,
                   actor_user_id, target_user_id, payload, created_at
            FROM group_state_events
            WHERE group_id = ? AND state_version > ?
            ORDER BY state_version ASC
            LIMIT ?
            """;

        List<GroupStateEvent> events = new ArrayList<>();
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            stmt.setLong(2, sinceVersion);
            stmt.setInt(3, Math.min(maxEvents, 500));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    events.add(new GroupStateEvent(
                            rs.getString("event_id"),
                            rs.getString("group_id"),
                            rs.getLong("state_version"),
                            rs.getString("event_type"),
                            rs.getString("actor_user_id"),
                            rs.getString("target_user_id"),
                            rs.getString("payload"),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.severe("getEventsSinceVersion error: " + e.getMessage());
        }

        return events;
    }

    /**
     * Updates a member's last_synced_version.
     * Called after client confirms sync.
     * Used to track which members need catch-up.
     */
    public void updateMemberSyncVersion(String groupId, String userId,
                                         long version) {
        String sql = """
            UPDATE group_members
            SET last_synced_version = ?, last_synced_at = CURRENT_TIMESTAMP(6)
            WHERE group_id = ? AND user_id = ?
            """;
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, version);
            stmt.setString(2, groupId);
            stmt.setString(3, userId);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            logger.warning("updateMemberSyncVersion error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private long incrementVersion(Connection conn, String groupId)
            throws SQLException {
        String sql = """
            UPDATE `groups`
            SET state_version = state_version + 1
            WHERE group_id = ?
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.executeUpdate();
        }

        String readSql = "SELECT state_version FROM `groups` WHERE group_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(readSql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to read incremented version");
    }

    private void bumpMemberCount(Connection conn, String groupId, int delta)
            throws SQLException {
        String sql = """
            UPDATE `groups`
            SET member_count = GREATEST(0, member_count + ?)
            WHERE group_id = ?
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, delta);
            stmt.setString(2, groupId);
            stmt.executeUpdate();
        }
    }

    private void insertEvent(Connection conn, String groupId,
                              long version, String eventType,
                              String actorUserId, String targetUserId,
                              String payload) throws SQLException {

        String sql = """
            INSERT INTO group_state_events (
                event_id, group_id, state_version, event_type,
                actor_user_id, target_user_id, payload
            ) VALUES (UUID(), ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setLong(2, version);
            stmt.setString(3, eventType);
            stmt.setString(4, actorUserId);
            stmt.setString(5, targetUserId);
            stmt.setString(6, payload);
            stmt.executeUpdate();
        }
    }

    private Group mapGroup(ResultSet rs) throws SQLException {
        return new Group(
                rs.getString("group_id"),
                rs.getString("jid"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("avatar_url"),
                rs.getString("creator_user_id"),
                GroupVisibility.fromString(rs.getString("visibility")),
                rs.getInt("max_members"),
                rs.getInt("member_count"),
                rs.getLong("state_version"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    @SuppressWarnings("NewApi")
    private GroupMember mapMember(ResultSet rs) throws SQLException {
        Timestamp muted = rs.getTimestamp("muted_until");
        return new GroupMember(
                rs.getString("user_id"),
                rs.getString("user_jid"),
                rs.getString("phone_number"),
                rs.getString("avatar_url"),
                rs.getString("display_status"),
                rs.getString("display_name"),
                rs.getBoolean("is_owner"),
                rs.getBoolean("is_admin"),
                muted != null ? muted.toInstant() : null,
                rs.getTimestamp("joined_at").toInstant(),
                rs.getString("added_by_user_id")
        );
    }

    private void rollback(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void close(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}