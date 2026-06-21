package streammessenger.group;


import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.push.PushNotificationService;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Manages group messaging operations.
 * <p>
 * Group JID format: g_7f3a9b2c@conference.yourdomain.com
 * <p>
 * Message routing:
 *   Sender → Server → ALL group members
 *   Online members: direct XMPP delivery
 *   Offline members: stored + push notification
 * <p>
 * Roles:
 *   owner  → Can delete group, promote admins, change all settings
 *   admin  → Can add/remove members, change name/avatar
 *   member → Can send messages, leave group
 */
public final class GroupManager {

    private static final Logger logger =
            Logger.getLogger(GroupManager.class.getName());

    private static final String GROUP_NS = "urn:xmpp:group:0";
    private static final int MAX_GROUP_NAME  = 100;
    private static final int MAX_MEMBERS     = 1024;
    private static final int MAX_DESC_LENGTH = 500;

    private final ConnectionPool pool;
    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final PushNotificationService pushService;

    public GroupManager(ConnectionPool pool,
                         DatabaseManager db,
                         SessionRegistry registry,
                         PushNotificationService pushService) {
        this.pool        = pool;
        this.db          = db;
        this.registry    = registry;
        this.pushService = pushService;
    }

    // =========================================================================
    // Records
    // =========================================================================

    public record GroupRecord(
            String groupId,
            String jid,
            String name,
            String description,
            String avatarUrl,
            String groupType,     // standard | broadcast | channel
            int memberCount,
            String createdAt
    ) {}

    public record GroupMember(
            String userId,
            String jid,
            String displayName,
            String avatarUrl,
            String role,          // owner | admin | member
            String joinedAt,
            boolean muted
    ) {}

    // =========================================================================
    // Create Group
    // =========================================================================

    /**
     * Creates a new group and adds the creator as owner.
     */
    public GroupRecord createGroup(String creatorUserId,
                                    String name,
                                    String description,
                                    List<String> memberUserIds,
                                    String groupType) {

        if (name == null || name.isBlank()) return null;
        if (name.length() > MAX_GROUP_NAME) return null;

        String groupId = generateGroupId();
        String jid = groupId + "@conference."
                + System.getProperty("xmpp.domain", "localhost");

        String sql = """
            INSERT INTO groups (
                group_id, jid, name, description,
                group_type, creator_user_id,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
            RETURNING group_id, jid, name, description,
                      group_type, created_at::text
            """;

        try (Connection conn = pool.getConnection()) {

            GroupRecord group;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, groupId);
                stmt.setString(2, jid);
                stmt.setString(3, name);
                stmt.setString(4, description);
                stmt.setString(5, groupType != null
                        ? groupType : "standard");
                stmt.setString(6, creatorUserId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) return null;
                    group = new GroupRecord(
                            rs.getString("group_id"),
                            rs.getString("jid"),
                            rs.getString("name"),
                            rs.getString("description"),
                            null,
                            rs.getString("group_type"),
                            0,
                            rs.getString("created_at")
                    );
                }
            }

            // Add creator as owner
            addMember(conn, groupId, creatorUserId, "owner");

            // Add initial members
            int added = 0;
            for (String memberId : memberUserIds) {
                if (!memberId.equals(creatorUserId) && added < MAX_MEMBERS) {
                    addMember(conn, groupId, memberId, "member");
                    added++;
                }
            }

            conn.commit();

            // Notify all members
            notifyMembersOfGroupCreation(group, creatorUserId,
                    memberUserIds);

            logger.info("Group created: groupId=" + groupId
                    + " name=" + name + " members=" + (added + 1));

            return group;

        } catch (SQLException e) {
            logger.severe("createGroup error: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Send Group Message
    // =========================================================================

    /**
     * Routes an encrypted group message to all group members.
     *
     * Online members: direct XMPP delivery
     * Offline members: stored in messages table + push notification
     *
     * The message is sent once to the server, then the server
     * fans it out to all N members. Client sends it only once.
     */
    public void routeGroupMessage(String senderUserId,
                                   String groupId,
                                   String messageId,
                                   String messageType,
                                   String encryptedContent,
                                   String iv,
                                   String mediaStorageKey,
                                   String mimeType,
                                   long fileSizeBytes,
                                   String fromJid,
                                   String groupJid) {

        List<GroupMember> members = getGroupMembers(groupId);
        if (members.isEmpty()) return;

        String groupName = getGroupName(groupId);

        // Build the stanza XML (same for all recipients)
        String stanza = buildGroupMessageStanza(
                messageId, fromJid, groupJid,
                messageType, encryptedContent, iv,
                mediaStorageKey, mimeType, fileSizeBytes
        );

        int delivered = 0;
        int stored    = 0;

        for (GroupMember member : members) {
            // Don't deliver to sender
            if (member.userId().equals(senderUserId)) continue;

            // Check if muted
            if (member.muted()) continue;

            // Try online delivery
            boolean online = registry.getByContactId(member.jid())
                    .filter(Session::isAuthenticated)
                    .map(s -> s.writeXML(stanza))
                    .orElse(false);

            if (online) {
                delivered++;
            } else {
                // Store for offline delivery
                db.storeEncryptedMessage(
                        fromJid, member.jid(), messageId,
                        messageType, encryptedContent, iv,
                        mediaStorageKey, null, mimeType,
                        fileSizeBytes, null
                );

                // Push notification
                pushService.sendGroupMessageNotification(
                        member.userId(), groupName,
                        getSenderDisplayName(senderUserId),
                        messageType
                );

                stored++;
            }
        }

        logger.fine("Group message routed: groupId=" + groupId
                + " delivered=" + delivered + " stored=" + stored);
    }

    // =========================================================================
    // Member Management
    // =========================================================================

    /**
     * Adds a member to a group.
     * Only admins/owners can add members (unless invite_mode = 'anyone').
     */
    public boolean addGroupMember(String groupId,
                                   String requesterUserId,
                                   String newMemberUserId) {

        // Check requester has permission
        String requesterRole = getMemberRole(groupId, requesterUserId);
        if (requesterRole == null) return false; // Not in group

        GroupConfig config = getGroupConfig(groupId);
        if ("admin_only".equals(config.inviteMode())
                && "member".equals(requesterRole)) {
            return false; // No permission
        }

        // Check group size
        if (getGroupMemberCount(groupId) >= MAX_MEMBERS) return false;

        try (Connection conn = pool.getConnection()) {
            addMember(conn, groupId, newMemberUserId, "member");
            conn.commit();

            // Notify existing members
            notifyMembersOfNewMember(groupId, newMemberUserId,
                    requesterUserId);

            return true;

        } catch (SQLException e) {
            logger.severe("addGroupMember error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes a member from a group.
     * Admins can remove members. Owner can remove admins.
     * Anyone can remove themselves (leave).
     */
    public boolean removeGroupMember(String groupId,
                                      String requesterUserId,
                                      String targetUserId) {

        String requesterRole = getMemberRole(groupId, requesterUserId);
        String targetRole    = getMemberRole(groupId, targetUserId);

        if (requesterRole == null) return false;

        // Self-removal (leave group) is always allowed
        boolean isSelf = requesterUserId.equals(targetUserId);
        if (!isSelf) {
            // Only owner can remove admins
            if ("admin".equals(targetRole)
                    && !"owner".equals(requesterRole)) {
                return false;
            }
            // Admins can remove members
            if ("member".equals(targetRole)
                    && "member".equals(requesterRole)) {
                return false;
            }
        }

        String sql = """
            UPDATE group_members
            SET left_at    = NOW(),
                removed_by = ?
            WHERE group_id  = ?
              AND user_id   = ?
              AND left_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, isSelf ? null : requesterUserId);
            stmt.setString(2, groupId);
            stmt.setString(3, targetUserId);
            int rows = stmt.executeUpdate();
            conn.commit();

            if (rows > 0) {
                // Send system message to group
                broadcastSystemMessage(groupId,
                        isSelf
                            ? getUserDisplayName(targetUserId) + " left"
                            : getUserDisplayName(targetUserId) + " was removed"
                );
            }

            return rows > 0;

        } catch (SQLException e) {
            logger.severe("removeGroupMember error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Promotes a member to admin or demotes admin to member.
     * Only the group owner can do this.
     */
    public boolean setMemberRole(String groupId,
                                  String ownerUserId,
                                  String targetUserId,
                                  String newRole) {

        if (!"owner".equals(getMemberRole(groupId, ownerUserId))) {
            return false;
        }
        if ("owner".equals(newRole)) return false; // Can't create new owner
        if (!"admin".equals(newRole) && !"member".equals(newRole)) {
            return false;
        }

        String sql = """
            UPDATE group_members
            SET role = ?
            WHERE group_id = ? AND user_id = ? AND left_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newRole);
            stmt.setString(2, groupId);
            stmt.setString(3, targetUserId);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("setMemberRole error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Group Info
    // =========================================================================

    public List<GroupMember> getGroupMembers(String groupId) {
        String sql = """
            SELECT
                gm.user_id,
                gm.role,
                gm.joined_at::text,
                gm.muted_until,
                u.jid,
                u.display_name,
                u.avatar_url
            FROM group_members gm
            INNER JOIN users u ON u.user_id = gm.user_id
            WHERE gm.group_id = ?
              AND gm.left_at IS NULL
              AND u.active = true
            ORDER BY gm.role DESC, gm.joined_at ASC
            """;

        List<GroupMember> members = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp mutedUntil = rs.getTimestamp("muted_until");
                    boolean muted = mutedUntil != null
                            && mutedUntil.after(new Timestamp(
                                    System.currentTimeMillis()));

                    members.add(new GroupMember(
                            rs.getString("user_id"),
                            rs.getString("jid"),
                            rs.getString("display_name"),
                            rs.getString("avatar_url"),
                            rs.getString("role"),
                            rs.getString("joined_at"),
                            muted
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("getGroupMembers error: " + e.getMessage());
        }

        return members;
    }

    public List<GroupRecord> getUserGroups(String userId) {
        String sql = """
            SELECT
                g.group_id,
                g.jid,
                g.name,
                g.description,
                g.avatar_url,
                g.group_type,
                g.created_at::text,
                (SELECT COUNT(*) FROM group_members gm2
                 WHERE gm2.group_id = g.group_id
                   AND gm2.left_at IS NULL) AS member_count
            FROM groups g
            INNER JOIN group_members gm
                ON gm.group_id = g.group_id
               AND gm.user_id  = ?
               AND gm.left_at IS NULL
            WHERE g.deleted_at IS NULL
            ORDER BY g.updated_at DESC
            """;

        List<GroupRecord> groups = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    groups.add(new GroupRecord(
                            rs.getString("group_id"),
                            rs.getString("jid"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("avatar_url"),
                            rs.getString("group_type"),
                            rs.getInt("member_count"),
                            rs.getString("created_at")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("getUserGroups error: " + e.getMessage());
        }

        return groups;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void addMember(Connection conn,
                            String groupId,
                            String userId,
                            String role) throws SQLException {
        String sql = """
            INSERT INTO group_members (group_id, user_id, role, joined_at)
            VALUES (?, ?, ?, NOW())
            ON CONFLICT (group_id, user_id) DO UPDATE
            SET left_at = NULL, role = EXCLUDED.role, joined_at = NOW()
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setString(2, userId);
            stmt.setString(3, role);
            stmt.executeUpdate();
        }
    }

    private String buildGroupMessageStanza(String messageId,
                                            String fromJid,
                                            String groupJid,
                                            String msgType,
                                            String encrypted,
                                            String iv,
                                            String storageKey,
                                            String mime,
                                            long size) {
        return String.format(
            "<message id='%s' from='%s' to='%s' type='groupchat'>" +
            "<encrypted xmlns='urn:xmpp:e2ee:0'" +
            " msg_type='%s' iv='%s'%s%s%s>%s</encrypted>" +
            "</message>",
            messageId, fromJid, groupJid,
            msgType, iv,
            storageKey != null ? " storage_key='" + storageKey + "'" : "",
            mime != null ? " mime='" + mime + "'" : "",
            size > 0 ? " size='" + size + "'" : "",
            encrypted
        );
    }

    private void broadcastSystemMessage(String groupId, String text) {
        String stanza = String.format(
            "<message type='groupchat'>" +
            "<body>%s</body>" +
            "<system xmlns='urn:xmpp:group:0'/>" +
            "</message>",
            text
        );

        for (GroupMember member : getGroupMembers(groupId)) {
            registry.getByContactId(member.jid())
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(stanza));
        }
    }

    private void notifyMembersOfGroupCreation(GroupRecord group,
                                               String creatorId,
                                               List<String> memberIds) {
        String notification = String.format(
            "<message type='headline'>" +
            "<group-invite xmlns='%s'>" +
            "<group_id>%s</group_id>" +
            "<group_name>%s</group_name>" +
            "<group_jid>%s</group_jid>" +
            "</group-invite></message>",
            GROUP_NS, group.groupId(), group.name(), group.jid()
        );

        for (String memberId : memberIds) {
            if (memberId.equals(creatorId)) continue;
            String jid = db.getJidByUserId(memberId);
            if (jid == null) continue;

            boolean online = registry.getByContactId(jid)
                    .filter(Session::isAuthenticated)
                    .map(s -> s.writeXML(notification))
                    .orElse(false);

            if (!online) {
                pushService.sendGroupMessageNotification(
                        memberId,
                        group.name(),
                        getUserDisplayName(creatorId),
                        "group_invite"
                );
            }
        }
    }

    private void notifyMembersOfNewMember(String groupId,
                                          String newMemberUserId,
                                          String addedByUserId) {
        String newMemberName   = getUserDisplayName(newMemberUserId);
        String addedByName     = getUserDisplayName(addedByUserId);

        String notification = String.format(
                "<message type='groupchat'>" +
                        "<group-event xmlns='%s'>" +
                        "<type>member_added</type>" +
                        "<group_id>%s</group_id>" +
                        "<user_id>%s</user_id>" +
                        "<display_name>%s</display_name>" +
                        "<added_by>%s</added_by>" +
                        "</group-event></message>",
                GROUP_NS, groupId,
                newMemberUserId, newMemberName, addedByName
        );

        for (GroupMember member : getGroupMembers(groupId)) {
            registry.getByContactId(member.jid())
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(notification));
        }
    }

    private String getMemberRole(String groupId, String userId) {
        String sql = """
            SELECT role FROM group_members
            WHERE group_id = ? AND user_id = ? AND left_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("role") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private int getGroupMemberCount(String groupId) {
        String sql = """
            SELECT COUNT(*) FROM group_members
            WHERE group_id = ? AND left_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private String getGroupName(String groupId) {
        String sql = "SELECT name FROM groups WHERE group_id = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("name") : "Group";
            }
        } catch (SQLException e) {
            return "Group";
        }
    }

    private String getUserDisplayName(String userId) {
        String sql = """
            SELECT display_name FROM users WHERE user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? rs.getString("display_name")
                        : "Unknown";
            }
        } catch (SQLException e) {
            return "Unknown";
        }
    }

    private String getSenderDisplayName(String userId) {
        return getUserDisplayName(userId);
    }

    private record GroupConfig(String inviteMode) {}

    private GroupConfig getGroupConfig(String groupId) {
        String sql = "SELECT invite_mode FROM groups WHERE group_id = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? new GroupConfig(rs.getString("invite_mode"))
                        : new GroupConfig("anyone");
            }
        } catch (SQLException e) {
            return new GroupConfig("anyone");
        }
    }

    private String generateGroupId() {
        byte[] bytes = new byte[4];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder("g_");
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}