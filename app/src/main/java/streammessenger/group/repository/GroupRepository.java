package streammessenger.group.repository;

import com.xmpp.db.ConnectionPool;
import com.xmpp.group.model.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persistence layer for groups.
 *
 * Key design decisions:
 *  - Every state-changing operation increments groups.state_version
 *  - Every state-changing operation inserts a row into group_state_events
 *  - Both happen in the same transaction
 *  - Clients use state_version for incremental sync
 */
public final class GroupRepository {

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

    /**
     * Creates a new group.
     *
     * Transaction:
     *   1. INSERT group (state_version = 1)
     *   2. INSERT default settings
     *   3. INSERT creator as member with is_owner = true
     *   4. INSERT group_created event at version 1
     *
     * Returns the created group.
     */
    public Group create(String name, String description,
                         String creatorUserId, String creatorJid,
                         GroupVisibility visibility, int maxMembers) {

        String groupId = UUID.randomUUID().toString();
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
            SELECT gm.user_id, gm.user_jid, u.display_name,
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
            SELECT gm.user_id, gm.user_jid, u.display_name,
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
     *
     * Transaction:
     *   1. INSERT member (handles re-adding via ON DUPLICATE)
     *   2. UPDATE groups.member_count and increment state_version
     *   3. INSERT member_added event
     *
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

            insertEvent(conn, groupId, newVersion, "metadata_changed",
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

    // =========================================================================
    // Delta sync - the key WhatsApp pattern
    // =========================================================================

    /**
     * Returns all events for a group since a given version.
     *
     * Used during delta sync:
     *   Client sends: "I have group X at version 5"
     *   Server returns: all events from version 6 onwards
     *
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

    private GroupMember mapMember(ResultSet rs) throws SQLException {
        Timestamp muted = rs.getTimestamp("muted_until");
        return new GroupMember(
                rs.getString("user_id"),
                rs.getString("user_jid"),
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