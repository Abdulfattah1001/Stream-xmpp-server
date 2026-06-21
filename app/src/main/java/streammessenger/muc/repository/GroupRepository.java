package streammessenger.muc.repository;

import com.xmpp.db.ConnectionPool;
import com.xmpp.muc.model.*;

import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * JDBC-based repository for group operations.
 *
 * Pure JDBC - no JPA, no Hibernate, no Spring.
 * Uses our existing ConnectionPool.
 * Every method gets a connection, does its work, returns it.
 *
 * Transactions: explicit BEGIN/COMMIT/ROLLBACK via Connection.setAutoCommit.
 */
public final class GroupRepository {

    private static final Logger logger =
            Logger.getLogger(GroupRepository.class.getName());

    private static final SecureRandom secureRandom = new SecureRandom();

    private final ConnectionPool pool;
    private final String mucDomain;

    public GroupRepository(ConnectionPool pool, String mucDomain) {
        this.pool      = pool;
        this.mucDomain = mucDomain;
    }

    // =========================================================================
    // Group ID generation
    // =========================================================================

    /**
     * Generates a UUID v4 for new groups.
     * Collision probability is essentially zero (2^122 space).
     */
    private String generateGroupId() {
        return UUID.randomUUID().toString();
    }

    // =========================================================================
    // Create group
    // =========================================================================

    /**
     * Creates a new group with default settings.
     * Creator is automatically added as owner.
     *
     * Transaction:
     *   1. INSERT INTO groups
     *   2. INSERT INTO group_settings (defaults)
     *   3. INSERT INTO group_members (creator as owner)
     *   4. INSERT INTO group_events (group_created)
     *
     * All-or-nothing - any failure rolls back everything.
     */
    public GroupRecord createGroup(String name,
                                    String description,
                                    String creatorUserId,
                                    GroupVisibility visibility,
                                    int maxMembers) {

        String groupId = generateGroupId();
        String jid     = groupId + "@" + mucDomain;

        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // 1. Insert group
            String groupSql = """
                INSERT INTO `groups` (
                    group_id, jid, name, description,
                    creator_user_id, visibility,
                    max_members, member_count, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'unlocked')
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
                    group_id, only_admins_can_send, only_admins_can_edit_meta,
                    only_admins_can_add, membership_approval, announcement_mode,
                    allow_history, history_max_messages, disappearing_seconds
                ) VALUES (?, FALSE, TRUE, FALSE, FALSE, FALSE, TRUE, 50, 0)
                """;
            try (PreparedStatement stmt = conn.prepareStatement(settingsSql)) {
                stmt.setString(1, groupId);
                stmt.executeUpdate();
            }

            // 3. Creator as owner
            String memberSql = """
                INSERT INTO group_members (
                    group_id, user_id, user_jid,
                    affiliation, joined_at
                )
                SELECT ?, ?, jid, 'owner', CURRENT_TIMESTAMP(6)
                FROM users WHERE user_id = ?
                """;
            try (PreparedStatement stmt = conn.prepareStatement(memberSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, creatorUserId);
                stmt.setString(3, creatorUserId);
                stmt.executeUpdate();
            }

            // 4. Audit event
            insertEvent(conn, groupId, "group_created",
                    creatorUserId, null,
                    String.format("{\"name\":\"%s\"}", escapeJson(name)));

            conn.commit();

            logger.info("Group created: groupId=" + groupId
                    + " creator=" + creatorUserId);

            return new GroupRecord(
                    groupId, jid, name, description, null,
                    creatorUserId, visibility, maxMembers, 1,
                    Instant.now(), null
            );

        } catch (SQLException e) {
            rollbackQuietly(conn);
            logger.severe("createGroup error: " + e.getMessage());
            throw new RuntimeException("Failed to create group", e);
        } finally {
            closeQuietly(conn);
        }
    }

    // =========================================================================
    // Get group
    // =========================================================================

    public GroupRecord getGroup(String groupId) {
        String sql = """
            SELECT
                g.group_id, g.jid, g.name, g.description, g.avatar_url,
                g.creator_user_id, g.visibility, g.max_members,
                g.member_count, g.created_at, g.deleted_at
            FROM `groups` g
            WHERE g.group_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapGroupRecord(rs);
            }
        } catch (SQLException e) {
            logger.severe("getGroup error: " + e.getMessage());
            return null;
        }
    }

    public GroupSettings getSettings(String groupId) {
        String sql = """
            SELECT only_admins_can_send, only_admins_can_edit_meta,
                   only_admins_can_add, membership_approval,
                   announcement_mode, allow_history,
                   history_max_messages, disappearing_seconds
            FROM group_settings
            WHERE group_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return GroupSettings.defaults();
                return new GroupSettings(
                        rs.getBoolean("only_admins_can_send"),
                        rs.getBoolean("only_admins_can_edit_meta"),
                        rs.getBoolean("only_admins_can_add"),
                        rs.getBoolean("membership_approval"),
                        rs.getBoolean("announcement_mode"),
                        rs.getBoolean("allow_history"),
                        rs.getInt("history_max_messages"),
                        rs.getInt("disappearing_seconds")
                );
            }
        } catch (SQLException e) {
            logger.severe("getSettings error: " + e.getMessage());
            return GroupSettings.defaults();
        }
    }

    // =========================================================================
    // Membership
    // =========================================================================

    public MemberRecord getMember(String groupId, String userId) {
        String sql = """
            SELECT user_id, user_jid, affiliation, nickname,
                   joined_at, last_active_at, muted_until, left_at
            FROM group_members
            WHERE group_id = ? AND user_id = ? AND left_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapMemberRecord(rs);
            }
        } catch (SQLException e) {
            logger.severe("getMember error: " + e.getMessage());
            return null;
        }
    }

    public List<MemberRecord> listMembers(String groupId, int limit, int offset) {
        String sql = """
            SELECT user_id, user_jid, affiliation, nickname,
                   joined_at, last_active_at, muted_until, left_at
            FROM group_members
            WHERE group_id = ? AND left_at IS NULL
            ORDER BY
                FIELD(affiliation, 'owner', 'admin', 'member', 'outcast'),
                joined_at ASC
            LIMIT ? OFFSET ?
            """;

        List<MemberRecord> members = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            stmt.setInt(2, Math.min(limit, 500));
            stmt.setInt(3, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) members.add(mapMemberRecord(rs));
            }
        } catch (SQLException e) {
            logger.severe("listMembers error: " + e.getMessage());
        }

        return members;
    }

    /**
     * Adds a member to the group.
     *
     * Uses INSERT ... ON DUPLICATE KEY UPDATE to handle the case where
     * the user was previously removed (left_at IS NOT NULL).
     *
     * Returns true if a NEW member was added (count should increment).
     */
    public boolean addMember(String groupId, String userId,
                              String userJid, Affiliation affiliation,
                              String invitedByUserId) {

        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String memberSql = """
                INSERT INTO group_members (
                    group_id, user_id, user_jid, affiliation,
                    invited_by_user_id, joined_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    affiliation         = VALUES(affiliation),
                    invited_by_user_id  = VALUES(invited_by_user_id),
                    joined_at           = CURRENT_TIMESTAMP(6),
                    left_at             = NULL,
                    removed_by_user_id  = NULL
                """;

            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(memberSql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, userId);
                stmt.setString(3, userJid);
                stmt.setString(4, affiliation.xmlValue());
                stmt.setString(5, invitedByUserId);
                rows = stmt.executeUpdate();
            }

            // MySQL returns 1 for INSERT, 2 for UPDATE on ON DUPLICATE KEY
            boolean isNew = (rows == 1);

            // Increment member count if truly new
            if (isNew) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE `groups` SET member_count = member_count + 1 " +
                        "WHERE group_id = ?")) {
                    stmt.setString(1, groupId);
                    stmt.executeUpdate();
                }
            }

            insertEvent(conn, groupId, "member_added",
                    invitedByUserId, userId,
                    String.format("{\"affiliation\":\"%s\"}",
                            affiliation.xmlValue()));

            conn.commit();
            return isNew;

        } catch (SQLException e) {
            rollbackQuietly(conn);
            logger.severe("addMember error: " + e.getMessage());
            throw new RuntimeException("Failed to add member", e);
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * Removes a member (soft delete - sets left_at).
     * Decrements member count.
     */
    public boolean removeMember(String groupId, String userId,
                                 String removedByUserId) {
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
                stmt.setString(1, removedByUserId);
                stmt.setString(2, groupId);
                stmt.setString(3, userId);
                rows = stmt.executeUpdate();
            }

            if (rows == 0) {
                conn.commit();
                return false;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE `groups` SET member_count = " +
                    "GREATEST(0, member_count - 1) WHERE group_id = ?")) {
                stmt.setString(1, groupId);
                stmt.executeUpdate();
            }

            insertEvent(conn, groupId, "member_removed",
                    removedByUserId, userId, null);

            conn.commit();
            return true;

        } catch (SQLException e) {
            rollbackQuietly(conn);
            logger.severe("removeMember error: " + e.getMessage());
            throw new RuntimeException("Failed to remove member", e);
        } finally {
            closeQuietly(conn);
        }
    }

    public boolean updateAffiliation(String groupId, String userId,
                                      Affiliation newAffiliation,
                                      String actorUserId) {
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String sql = """
                UPDATE group_members
                SET affiliation = ?
                WHERE group_id = ? AND user_id = ? AND left_at IS NULL
                """;

            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newAffiliation.xmlValue());
                stmt.setString(2, groupId);
                stmt.setString(3, userId);
                rows = stmt.executeUpdate();
            }

            if (rows > 0) {
                insertEvent(conn, groupId, "affiliation_changed",
                        actorUserId, userId,
                        String.format("{\"new_affiliation\":\"%s\"}",
                                newAffiliation.xmlValue()));
            }

            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            rollbackQuietly(conn);
            logger.severe("updateAffiliation error: " + e.getMessage());
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    // =========================================================================
    // Settings
    // =========================================================================

    public boolean updateSettings(String groupId, GroupSettings settings,
                                   String actorUserId) {
        String sql = """
            UPDATE group_settings SET
                only_admins_can_send       = ?,
                only_admins_can_edit_meta  = ?,
                only_admins_can_add        = ?,
                membership_approval        = ?,
                announcement_mode          = ?,
                allow_history              = ?,
                history_max_messages       = ?,
                disappearing_seconds       = ?,
                updated_by_user_id         = ?
            WHERE group_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, settings.onlyAdminsCanSend());
            stmt.setBoolean(2, settings.onlyAdminsCanEditMeta());
            stmt.setBoolean(3, settings.onlyAdminsCanAdd());
            stmt.setBoolean(4, settings.membershipApproval());
            stmt.setBoolean(5, settings.announcementMode());
            stmt.setBoolean(6, settings.allowHistory());
            stmt.setInt(7, settings.historyMaxMessages());
            stmt.setInt(8, settings.disappearingSeconds());
            stmt.setString(9, actorUserId);
            stmt.setString(10, groupId);

            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("updateSettings error: " + e.getMessage());
            return false;
        }
    }

    public boolean updateMetadata(String groupId, String name,
                                   String description, String avatarUrl,
                                   String actorUserId) {
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

            if (params.isEmpty()) { conn.commit(); return false; }

            sql.append("updated_at = CURRENT_TIMESTAMP(6) WHERE group_id = ?");
            params.add(groupId);

            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                rows = stmt.executeUpdate();
            }

            if (rows > 0) {
                insertEvent(conn, groupId, "metadata_updated",
                        actorUserId, null, null);
            }

            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            rollbackQuietly(conn);
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void insertEvent(Connection conn, String groupId,
                              String eventType, String actorUserId,
                              String targetUserId, String payload)
            throws SQLException {
        String sql = """
            INSERT INTO group_events (
                event_id, group_id, event_type, actor_user_id,
                target_user_id, payload
            ) VALUES (UUID(), ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setString(2, eventType);
            stmt.setString(3, actorUserId);
            stmt.setString(4, targetUserId);
            stmt.setString(5, payload);
            stmt.executeUpdate();
        }
    }

    private GroupRecord mapGroupRecord(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new GroupRecord(
                rs.getString("group_id"),
                rs.getString("jid"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("avatar_url"),
                rs.getString("creator_user_id"),
                GroupVisibility.fromString(rs.getString("visibility")),
                rs.getInt("max_members"),
                rs.getInt("member_count"),
                rs.getTimestamp("created_at").toInstant(),
                deletedAt != null ? deletedAt.toInstant() : null
        );
    }

    private MemberRecord mapMemberRecord(ResultSet rs) throws SQLException {
        Timestamp mutedUntil = rs.getTimestamp("muted_until");
        return new MemberRecord(
                rs.getString("user_id"),
                rs.getString("user_jid"),
                Affiliation.fromXml(rs.getString("affiliation")),
                rs.getString("nickname"),
                rs.getTimestamp("joined_at").toInstant(),
                mutedUntil != null ? mutedUntil.toInstant() : null
        );
    }

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =========================================================================
    // Records
    // =========================================================================

    public record GroupRecord(
            String groupId, String jid, String name, String description,
            String avatarUrl, String creatorUserId, GroupVisibility visibility,
            int maxMembers, int memberCount, Instant createdAt,
            Instant deletedAt
    ) {}

    public record MemberRecord(
            String userId, String userJid, Affiliation affiliation,
            String nickname, Instant joinedAt, Instant mutedUntil
    ) {}
}
