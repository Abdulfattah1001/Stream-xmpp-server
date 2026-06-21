// New: ProfileEventBroadcaster.java

package streammessenger.features;


import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Broadcasts profile updates to all "interested parties":
 *
 *   - User's roster contacts
 *   - All members of groups the user is in
 *
 * Uses a publish/subscribe model:
 *   When user A updates their profile:
 *     1. Server collects all JIDs that should be notified
 *     2. For each online session: send a profile update stanza
 *     3. For offline sessions: queue notification (delivered on reconnect)
 *
 * This eliminates the need for clients to poll for profile changes.
 *
 * Custom namespace: urn:xmpp:profile:0
 */
public final class ProfileEventBroadcaster {

    private static final Logger logger =
            Logger.getLogger(ProfileEventBroadcaster.class.getName());

    private static final String PROFILE_NS = "urn:xmpp:profile:0";

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    /**
     * Bounded executor for fan-out to avoid blocking the IQ handler thread.
     */
    private final ExecutorService broadcastExecutor =
            new ThreadPoolExecutor(
                    4, 50, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(10000),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

    public ProfileEventBroadcaster(ConnectionPool pool,
                                    SessionRegistry registry) {
        this.pool     = pool;
        this.registry = registry;
    }

    /**
     * Called when a user updates their profile.
     *
     * @param userId      The user whose profile changed
     * @param userJid     Their JID
     * @param displayName New display name (or null if unchanged)
     * @param avatarUrl   New avatar URL (or null if unchanged)
     * @param statusMessage New status (or null if unchanged)
     */
    public void onProfileUpdated(String userId, String userJid,
                                   String displayName, String avatarUrl,
                                   String statusMessage) {

        broadcastExecutor.execute(() -> {
            try {
                Set<String> interestedJids =
                        collectInterestedJids(userId, userJid);

                if (interestedJids.isEmpty()) return;

                String updateStanza = buildProfileUpdateStanza(
                        userJid, displayName, avatarUrl, statusMessage);

                int delivered = 0;
                for (String targetJid : interestedJids) {
                    if (registry.getByContactId(targetJid)
                            .filter(Session::isAuthenticated)
                            .map(s -> s.writeXML(updateStanza))
                            .orElse(false)) {
                        delivered++;
                    }
                }

                logger.fine("Profile update broadcast: userId=" + userId
                        + " interested=" + interestedJids.size()
                        + " delivered=" + delivered);

            } catch (Exception e) {
                logger.warning("Profile broadcast error: " + e.getMessage());
            }
        });
    }

    /**
     * Collects all JIDs that should be notified of this user's profile change:
     *   1. Roster contacts with subscription 'from' or 'both'
     *   2. All active members of groups the user is in
     */
    private Set<String> collectInterestedJids(String userId, String userJid) {
        Set<String> result = new HashSet<>();

        // Roster contacts
        String rosterSql = """
            SELECT u.jid
            FROM roster_items ri
            INNER JOIN users u ON u.user_id = ri.owner_user_id
            WHERE ri.contact_user_id = ?
              AND ri.subscription IN ('from', 'both')
              AND ri.blocked = false
              AND u.active = true
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(rosterSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(rs.getString("jid"));
            }
        } catch (SQLException e) {
            logger.warning("Roster query error: " + e.getMessage());
        }

        // Group members - users who share at least one group
        String groupSql = """
            SELECT DISTINCT u.jid
            FROM group_members gm1
            INNER JOIN group_members gm2 ON gm2.group_id = gm1.group_id
            INNER JOIN users u ON u.user_id = gm2.user_id
            WHERE gm1.user_id = ?
              AND gm2.user_id != ?
              AND gm1.left_at IS NULL
              AND gm2.left_at IS NULL
              AND gm1.affiliation != 'outcast'
              AND gm2.affiliation != 'outcast'
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(groupSql)) {
            stmt.setString(1, userId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(rs.getString("jid"));
            }
        } catch (SQLException e) {
            logger.warning("Group members query error: " + e.getMessage());
        }

        result.remove(userJid); // never notify self
        return result;
    }

    private String buildProfileUpdateStanza(String userJid,
                                              String displayName,
                                              String avatarUrl,
                                              String statusMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<message type='headline'>" +
            "<profile-update xmlns='%s' jid='%s'>",
            PROFILE_NS, escapeXml(userJid)
        ));

        if (displayName != null) {
            sb.append("<display_name>")
              .append(escapeXml(displayName))
              .append("</display_name>");
        }
        if (avatarUrl != null) {
            sb.append("<avatar_url>")
              .append(escapeXml(avatarUrl))
              .append("</avatar_url>");
        }
        if (statusMessage != null) {
            sb.append("<status_message>")
              .append(escapeXml(statusMessage))
              .append("</status_message>");
        }

        sb.append("</profile-update></message>");
        return sb.toString();
    }

    public void shutdown() {
        broadcastExecutor.shutdown();
        try {
            if (!broadcastExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                broadcastExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            broadcastExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}