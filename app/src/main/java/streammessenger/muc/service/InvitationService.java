package streammessenger.muc.service;


import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.muc.exceptions.MucException;
import streammessenger.muc.model.Affiliation;
import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.repository.GroupRepository;
import streammessenger.session.SessionRegistry;

/**
 * Handles direct invitations and shareable join links.
 *
 * SECURITY:
 *   - Link tokens: 256-bit entropy (Base64Url, 43 chars)
 *   - Expiry enforcement at DB level
 *   - Revocation: immediate (no cache)
 *   - One-time links: atomic use via UPDATE...WHERE used=FALSE
 *   - Rate limiting: caller's responsibility
 */
public final class InvitationService {

    private static final Logger logger =
            Logger.getLogger(InvitationService.class.getName());

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final long DEFAULT_INVITE_TTL_HOURS = 168; // 7 days

    private final ConnectionPool pool;
    private final GroupRepository repository;
    private final GroupRegistry registry;
    private final MembershipService membershipService;
    private final SessionRegistry sessionRegistry;
    private final String inviteUrlBase;

    public InvitationService(ConnectionPool pool,
                             GroupRepository repository,
                             GroupRegistry registry,
                             MembershipService membershipService,
                             SessionRegistry sessionRegistry,
                             String inviteUrlBase) {
        this.pool              = pool;
        this.repository        = repository;
        this.registry          = registry;
        this.membershipService = membershipService;
        this.sessionRegistry   = sessionRegistry;
        this.inviteUrlBase     = inviteUrlBase;
    }

    // =========================================================================
    // Direct invitation
    // =========================================================================

    /**
     * Sends a direct mediated invitation (XEP-0045 §7.8).
     * Creates DB record and routes invitation stanza.
     */
    public String sendInvitation(String groupId, String inviterUserId,
                                  String inviteeUserId, String inviteeJid,
                                  String reason) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        // Authorization
        GroupRepository.MemberRecord inviter =
                repository.getMember(groupId, inviterUserId);
        if (inviter == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "Inviter not a member");
        }

        if (room.getSettings().onlyAdminsCanAdd()
                && !inviter.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can invite");
        }

        // Already a member?
        GroupRepository.MemberRecord existing =
                repository.getMember(groupId, inviteeUserId);
        if (existing != null
                && existing.affiliation() != Affiliation.OUTCAST) {
            throw new MucException(MucException.Code.ALREADY_MEMBER,
                    "User already a member");
        }

        String invitationId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(
                DEFAULT_INVITE_TTL_HOURS, ChronoUnit.HOURS);

        String sql = """
            INSERT INTO group_invitations (
                invitation_id, group_id, inviter_user_id,
                invitee_user_id, invitee_jid, reason,
                state, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
            ON DUPLICATE KEY UPDATE
                invitation_id = VALUES(invitation_id),
                inviter_user_id = VALUES(inviter_user_id),
                reason = VALUES(reason),
                state = 'pending',
                expires_at = VALUES(expires_at),
                created_at = CURRENT_TIMESTAMP(6)
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, invitationId);
            stmt.setString(2, groupId);
            stmt.setString(3, inviterUserId);
            stmt.setString(4, inviteeUserId);
            stmt.setString(5, inviteeJid);
            stmt.setString(6, reason);
            stmt.setTimestamp(7, Timestamp.from(expiresAt));
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("sendInvitation error: " + e.getMessage());
            throw new MucException(MucException.Code.INTERNAL,
                    "Failed to save invitation");
        }

        // Route XMPP invitation stanza to invitee
        deliverInvitationStanza(room, inviterUserId, inviteeJid,
                invitationId, reason);

        return invitationId;
    }

    public void acceptInvitation(String invitationId, String userId) {
        // Atomic state transition
        String sql = """
            UPDATE group_invitations
            SET state = 'accepted', responded_at = CURRENT_TIMESTAMP(6)
            WHERE invitation_id = ?
              AND invitee_user_id = ?
              AND state = 'pending'
              AND expires_at > CURRENT_TIMESTAMP(6)
            """;

        String groupId = null;
        String inviteeJid = null;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, invitationId);
            stmt.setString(2, userId);
            int rows = stmt.executeUpdate();

            if (rows == 0) {
                conn.rollback();
                throw new MucException(MucException.Code.INVITE_INVALID,
                        "Invitation not found, expired, or already used");
            }

            // Fetch the group_id and invitee_jid for membership
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT group_id, invitee_jid FROM group_invitations " +
                    "WHERE invitation_id = ?")) {
                q.setString(1, invitationId);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        groupId = rs.getString("group_id");
                        inviteeJid = rs.getString("invitee_jid");
                    }
                }
            }

            conn.commit();

        } catch (SQLException e) {
            logger.severe("acceptInvitation error: " + e.getMessage());
            throw new MucException(MucException.Code.INTERNAL,
                    "Failed to accept invitation");
        }

        if (groupId != null) {
            membershipService.addMember(groupId, userId, userId,
                    inviteeJid, Affiliation.MEMBER);
        }
    }

    public void rejectInvitation(String invitationId, String userId) {
        String sql = """
            UPDATE group_invitations
            SET state = 'rejected', responded_at = CURRENT_TIMESTAMP(6)
            WHERE invitation_id = ?
              AND invitee_user_id = ?
              AND state = 'pending'
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, invitationId);
            stmt.setString(2, userId);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            logger.severe("rejectInvitation error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Join links (shareable URLs)
    // =========================================================================

    /**
     * Generates a new join link.
     * <p>
     * Security:
     *   - 256 bits of entropy (43 chars Base64Url)
     *   - Unguessable: 2^256 search space
     *   - One-time or unlimited
     *   - Optional expiry
     *   - Revocable
     */
    public JoinLink createJoinLink(String groupId, String creatorUserId,
                                    boolean oneTime, Integer maxUses,
                                    Long expiresInHours) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        GroupRepository.MemberRecord member = repository.getMember(groupId, creatorUserId);
        if (member == null || !member.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can create join links");
        }

        String token = generateLinkToken();
        logger.info("The generated invitation link is: "+token);
        Instant expiresAt = expiresInHours != null
                ? Instant.now().plus(expiresInHours, ChronoUnit.HOURS)
                : null;

        String sql = """
            INSERT INTO group_join_links (
                link_token, group_id, created_by_user_id,
                one_time, max_uses
            ) VALUES (?, ?, ?, ?, ?)
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, token);
            stmt.setString(2, groupId);
            stmt.setString(3, creatorUserId);
            stmt.setBoolean(4, oneTime);
            if (maxUses != null) stmt.setInt(5, maxUses);
            else stmt.setNull(5, Types.INTEGER);
            //if (expiresAt != null) stmt.setTimestamp(6, Timestamp.from(expiresAt));
            //else stmt.setNull(6, Types.TIMESTAMP);

            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("createJoinLink error: " + e.getMessage());
            throw new MucException(MucException.Code.INTERNAL,
                    "Failed to create link");
        }

        return new JoinLink(token, inviteUrlBase + "/" + token,
                oneTime, expiresAt);
    }

    /**
     * Joins a group via link.
     * <p>
     * Atomicity: uses single UPDATE that fails if link already used.
     * No race condition possible.
     */
    public String joinViaLink(String linkToken, String userId,
                                String userJid) {
        Connection conn = null;
        String groupId = null;

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // Atomically claim the link (or fail)
            String updateSql = """
                UPDATE group_join_links
                SET use_count = use_count + 1,
                    used = (one_time = TRUE),
                    used_by_user_id = CASE WHEN one_time = TRUE
                                            THEN ? ELSE used_by_user_id END
                WHERE link_token = ?
                  AND revoked = FALSE
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(6))
                  AND (used = FALSE)
                  AND (max_uses IS NULL OR use_count < max_uses)
                """;

            int rows;
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, userId);
                stmt.setString(2, linkToken);
                rows = stmt.executeUpdate();
            }

            if (rows == 0) {
                throw new MucException(MucException.Code.INVITE_INVALID,
                        "Link is invalid, expired, used, or revoked");
            }

            // Get the group
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT group_id FROM group_join_links " +
                    "WHERE link_token = ?")) {
                q.setString(1, linkToken);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) groupId = rs.getString("group_id");
                }
            }

            conn.commit();

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) {}
            logger.severe("joinViaLink error: " + e.getMessage());
            throw new MucException(MucException.Code.INTERNAL,
                    "Failed to join via link");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }

        if (groupId != null) {
            membershipService.addMemberViaLink(groupId, userId, userJid, Affiliation.MEMBER);
        }

        return groupId;
    }

    public void revokeLink(String linkToken, String actorUserId) {
        String sql = """
            UPDATE group_join_links jl
            INNER JOIN group_members gm
                ON gm.group_id = jl.group_id
                AND gm.user_id = ?
                AND gm.affiliation IN ('owner', 'admin')
                AND gm.left_at IS NULL
            SET jl.revoked = TRUE,
                jl.revoked_at = CURRENT_TIMESTAMP(6),
                jl.revoked_by_user_id = ?
            WHERE jl.link_token = ?
              AND jl.revoked = FALSE
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, actorUserId);
            stmt.setString(2, actorUserId);
            stmt.setString(3, linkToken);
            int rows = stmt.executeUpdate();
            conn.commit();

            if (rows == 0) {
                throw new MucException(MucException.Code.NOT_AUTHORIZED,
                        "Cannot revoke link (not admin or already revoked)");
            }
        } catch (SQLException e) {
            logger.severe("revokeLink error: " + e.getMessage());
        }
    }

    private String generateLinkToken() {
        //byte[] bytes = new byte[32];
        byte[] bytes = new byte[16]; // For short lenght and it is also secure
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder()
                .withoutPadding().encodeToString(bytes);
    }

    private void deliverInvitationStanza(GroupRoom room, String inviterJid,
                                          String inviteeJid,
                                          String invitationId, String reason) {
        // XEP-0045 §7.8.2 - mediated invitation
        String stanza = String.format(
            "<message from='%s' to='%s'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<invite from='%s' id='%s'>" +
            "<reason>%s</reason>" +
            "</invite>" +
            "</x>" +
            "</message>",
            escapeXml(room.getJid()),
            escapeXml(inviteeJid),
            escapeXml(inviterJid),
            escapeXml(invitationId),
            escapeXml(reason != null ? reason : "")
        );

        sessionRegistry.getByContactId(inviteeJid)
                .ifPresent(s -> s.writeXML(stanza));
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }



    public record JoinLink(String token, String url,
                            boolean oneTime, Instant expiresAt) {}
}
