package streammessenger.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background task that periodically cleans up expired data.
 * <p>
 * Schedule:
 *   Every 1 hour:
 *     - Delete expired status (> 24 hours old)
 *     - Delete expired offline messages (> 30 days old)
 *     - Delete orphaned media uploads (never attached, > 1 day old)
 *     - Delete expired stream management sessions
 *     - Delete expired Blog (>7 Days olds)
 * <p>
 * Why 30 days for offline messages:
 *   - WhatsApp: 30 days
 *   - Signal:   30 days
 *   - Telegram: unlimited (but stores on their servers)
 * <p>
 *   30 days balances:
 *     ✅ User gets messages after holiday
 *     ✅ User gets messages after phone repair
 *     ✅ Not storing data forever (GDPR friendly)
 *     ✅ Reasonable storage cost
 * <p>
 * Why 24 hours for status:
 *   - Instagram Stories: 24 hours
 *   - WhatsApp Status:   24 hours
 *   - Creates urgency to view before it disappears
 *   - Well understood by users
 */
public final class CleanupTask {

    private static final Logger logger =
            Logger.getLogger(CleanupTask.class.getName());

    // Offline message lifetime
    private static final int OFFLINE_MESSAGE_TTL_DAYS = 30;

    // Status lifetime (must match DB default of 24 hours)
    private static final int STATUS_TTL_HOURS = 24;

    private static final int BLOG_TTL_DAYS = 7;

    // SM session lifetime after disconnect
    private static final int SM_SESSION_TTL_MINUTES = 5;

    private final ConnectionPool pool;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "db-cleanup");
                t.setDaemon(true);
                return t;
            });

    public CleanupTask(ConnectionPool pool) {
        this.pool = pool;
    }

    public void start() {
        // Run cleanup every hour
        scheduler.scheduleAtFixedRate(
                this::runCleanup, 5, 60, TimeUnit.MINUTES);

        logger.info("Database cleanup task started. " +
                "Offline messages TTL: " + OFFLINE_MESSAGE_TTL_DAYS + " days. " +
                "Status TTL: " + STATUS_TTL_HOURS + " hours." +
                "Blog TTL: " + BLOG_TTL_DAYS + " hours.");
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Cleanup operations
    // =========================================================================

    private void runCleanup() {
        logger.info("Running scheduled database cleanup...");

        long start = System.currentTimeMillis();

        int expiredStatus   = cleanExpiredStatus();
        int expiredMessages = cleanExpiredOfflineMessages();
        int orphanedMedia   = cleanOrphanedMedia();
        int expiredSm       = cleanExpiredSmSessions();
        int expiredTokens   = cleanExpiredSessionTokens();

        long elapsed = System.currentTimeMillis() - start;

        logger.info(String.format(
            "Cleanup complete in %dms: " +
            "status=%d, messages=%d, media=%d, sm=%d, tokens=%d",
            elapsed,
            expiredStatus, expiredMessages,
            orphanedMedia, expiredSm, expiredTokens
        ));
    }

    /**
     * Deletes expired status entries.
     * <p>
     * A status expires 24 hours after creation.
     * expires_at is set in the DB on INSERT as NOW() + INTERVAL '24 hours'
     * <p>
     * Also deletes associated status_views and status_visibility_list
     * via CASCADE foreign keys.
     * <p>
     * Before deleting: collect media_storage_keys so we can
     * delete the actual media files from object storage.
     */
    private int cleanExpiredStatus() {
        // First: collect media keys for expired status so we can
        // clean up object storage too
        List<String> mediaKeysToDelete = collectExpiredStatusMediaKeys();

        String sql = """
            DELETE FROM user_status
            WHERE (expires_at < NOW() OR deleted_at IS NOT NULL)
              AND created_at < NOW() - INTERVAL '24 hours'
            """;

        int deleted = executeUpdate(sql, "cleanExpiredStatus");

        if (!mediaKeysToDelete.isEmpty()) {
            // Queue for async deletion from object storage
            // In production: send to a queue (SQS/RabbitMQ)
            // For now: log them
            logger.info("Media files to delete from storage: "
                    + mediaKeysToDelete.size());
            // TODO: storageClient.deleteFiles(mediaKeysToDelete);
        }

        return deleted;
    }

    /**
     * Deletes expired blog entries.
     * <p>
     * A blog expires 7 days after creation.
     * expires_at is set in the DB on INSERT as NOW() + INTERVAL '24 hours'
     * <p>
     * Also deletes associated blog_views and blog_visibility_list
     * via CASCADE foreign keys.
     * <p>
     * Before deleting: collect media_storage_keys so we can
     * delete the actual media files from object storage.
     */
    private int cleanExpiredBlog() {
        //TODO: To be implemented later
        return 0;
    }

    /**
     * Deletes offline messages that have exceeded the 30-day TTL.
     *
     * These are messages that were stored because the recipient
     * was offline and never came back online within 30 days.
     *
     * In a real app you'd want to:
     *   1. Send a push notification before deletion ("You have unread messages")
     *   2. Possibly notify the sender their message was never delivered
     */
    private int cleanExpiredOfflineMessages() {
        // Before deleting: find messages that need push notifications
        sendPushNotificationsForExpiringMessages();

        String sql = """
            DELETE FROM messages
            WHERE status  = 'pending'
              AND expires_at < NOW()
            """;

        return executeUpdate(sql, "cleanExpiredOfflineMessages");
    }

    /**
     * Sends push notifications for messages expiring in the next 24 hours.
     * Gives users a chance to read before deletion.
     */
    private void sendPushNotificationsForExpiringMessages() {
        String sql = """
            SELECT DISTINCT
                m.to_user_id,
                COUNT(*) AS unread_count,
                st.push_token,
                st.platform
            FROM messages m
            INNER JOIN session_tokens st
                ON st.user_id   = m.to_user_id
                AND st.revoked_at IS NULL
            WHERE m.status     = 'pending'
              AND m.expires_at BETWEEN NOW() AND NOW() + INTERVAL '24 hours'
              AND m.push_sent  = false
            GROUP BY m.to_user_id, st.push_token, st.platform
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String userId     = rs.getString("to_user_id");
                int    count      = rs.getInt("unread_count");
                String pushToken  = rs.getString("push_token");
                String platform   = rs.getString("platform");

                logger.info(String.format(
                    "Messages expiring soon: userId=%s count=%d",
                    userId, count));

                // TODO: Send push notification via FCM/APNs
                // pushService.send(pushToken, platform,
                //     "You have " + count + " unread messages expiring soon");

                // Mark as push_sent so we don't spam
                markMessagesPushSent(userId);
            }

        } catch (Exception e) {
            logger.warning("Push notification check failed: " + e.getMessage());
        }
    }

    private void markMessagesPushSent(String userId) {
        String sql = """
            UPDATE messages
            SET push_sent    = true,
                push_sent_at = NOW()
            WHERE to_user_id = ?
              AND status      = 'pending'
              AND expires_at BETWEEN NOW() AND NOW() + INTERVAL '24 hours'
            """;

        executeUpdateWithParam(sql, userId, "markMessagesPushSent");
    }

    /**
     * Deletes media upload records that were never attached to a message.
     * The actual files in object storage are deleted separately.
     */
    private int cleanOrphanedMedia() {
        String sql = """
            DELETE FROM media_uploads
            WHERE upload_state = 'pending'
              AND expires_at   < NOW()
            """;

        return executeUpdate(sql, "cleanOrphanedMedia");
    }

    /**
     * Deletes expired stream management sessions.
     * These are SM states saved for reconnection.
     * After the TTL, the client must start a fresh session.
     */
    private int cleanExpiredSmSessions() {
        String sql = """
            DELETE FROM sm_sessions
            WHERE expires_at < NOW()
            """;

        return executeUpdate(sql, "cleanExpiredSmSessions");
    }

    /**
     * Removes session tokens that have been revoked for more than 30 days.
     * Revoked tokens must be kept for audit purposes but not forever.
     */
    private int cleanExpiredSessionTokens() {
        String sql = """
                DELETE FROM session_tokens
                WHERE revoked_at < NOW() - INTERVAL 30 DAY
            """;

        return executeUpdate(sql, "cleanExpiredSessionTokens");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<String> collectExpiredStatusMediaKeys() {
        String sql = """
            SELECT media_storage_key
            FROM user_status
            WHERE media_storage_key IS NOT NULL
              AND (expires_at < NOW() OR deleted_at IS NOT NULL)
              AND created_at < NOW() - INTERVAL '24 hours'
            """;

        List<String> keys = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String key = rs.getString("media_storage_key");
                if (key != null) keys.add(key);
            }

        } catch (Exception e) {
            logger.warning("collectExpiredStatusMediaKeys error: "
                    + e.getMessage());
        }

        return keys;
    }

    private int executeUpdate(String sql, String operationName) {
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int rows = stmt.executeUpdate();
            conn.commit();

            if (rows > 0) {
                logger.info(operationName + ": deleted " + rows + " rows");
            }

            return rows;

        } catch (Exception e) {
            logger.warning(operationName + " failed: " + e.getMessage());
            return 0;
        }
    }

    private int executeUpdateWithParam(String sql,
                                        String param,
                                        String operationName) {
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, param);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows;

        } catch (Exception e) {
            logger.warning(operationName + " failed: " + e.getMessage());
            return 0;
        }
    }
}