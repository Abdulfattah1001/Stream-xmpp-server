package streammessenger.db;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Handles phonebook upload and contact discovery.
 * <p>
 * THE FLOW:
 * ─────────
 * <p>
 *
 * 1. App uploads hashed phone numbers from device phonebook
 * 2. We store all hashes (used later for two-way discovery)
 * 3. We return matching registered users
 * 4. For each match, we auto-create roster entries on BOTH sides:
 *    - "both" if both users have each other
 *    - "from"/"to" if only one direction
 * <p>
 * THE MAGIC OF AUTOMATIC ROSTER:
 * ──────────────────────────────
 * <p>
 *
 * When User A uploads their phonebook with Bob's number:
 *   - We check: does Bob's phonebook have A?
 *   - If YES → subscription='both' on both sides (mutual connection)
 *   - If NO  → A sees Bob as 'to' (subscribed to Bob's presence)
 *              Bob doesn't see A in roster yet
 * <p>
 * When Bob LATER uploads his phonebook with A's number:
 *   - We detect the mutual connection
 *   - Upgrade BOTH sides to 'both'
 *   - Send roster push to both
 */
public final class ContactDiscovery {

    private static final Logger logger =
            Logger.getLogger(ContactDiscovery.class.getName());

    private final ConnectionPool pool;

    public ContactDiscovery(ConnectionPool pool) {
        this.pool = pool;
    }

    // =========================================================================
    // Upload phonebook + return matches
    // =========================================================================

    /**
     * Called when user signs up or syncs their phonebook.
     *
     * @param userId           The user uploading
     * @param phoneHashEntries phone_hash → name (from device contacts)
     * @return List of matched users (registered + in phonebook)
     */
    public List<ContactMatch> uploadAndDiscover(
            String userId,
            Map<String, String> phoneHashEntries) {

        if (phoneHashEntries.isEmpty()) return new ArrayList<>();

        // Step 1: Store all uploaded hashes
        storePhonebookHashes(userId, phoneHashEntries);

        // Step 2: Find matches and create roster entries automatically
        return findMatchesAndCreateRoster(userId, phoneHashEntries.keySet());
    }

    /**
     * Stores hashed phone numbers from user's phonebook.
     */
    private void storePhonebookHashes(String userId,
                                       Map<String, String> hashes) {
        String sql = """
            INSERT INTO phonebook_uploads
                (user_id, phone_hash, contact_name, uploaded_at)
            VALUES (?, ?, ?, NOW())
            ON CONFLICT (user_id, phone_hash) DO UPDATE SET
                contact_name = EXCLUDED.contact_name,
                uploaded_at  = NOW()
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                stmt.setString(1, userId);
                stmt.setString(2, entry.getKey());
                stmt.setString(3, entry.getValue());
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("storePhonebookHashes error: " + e.getMessage());
        }
    }

    /**
     * Finds registered users matching the hashes AND creates roster entries.
     * <p>
     * For each match:
     *   - Check if mutual (does the other side have me too?)
     *   - Create/update roster entries accordingly
     *   - subscription='both'  → mutual contact (both have each other)
     *   - subscription='to'    → I have them, they don't have me
     */
    private List<ContactMatch> findMatchesAndCreateRoster(
            String requestingUserId,
            Set<String> phoneHashes) {

        // Single query that:
        //  1. Finds users matching hashes
        //  2. Checks if THEY have requesting user in their phonebook
        String matchSql = """
            WITH my_phone AS (
                SELECT phone_number_hash FROM users WHERE user_id = ?
            )
            SELECT
                u.user_id,
                u.jid,
                u.display_name,
                u.avatar_url,
                u.phone_number_hash,
                pb.contact_name AS my_label_for_them,
                EXISTS(
                    SELECT 1 FROM phonebook_uploads pu
                    WHERE pu.user_id    = u.user_id
                      AND pu.phone_hash = (SELECT phone_number_hash FROM my_phone)
                ) AS they_have_me
            FROM users u
            INNER JOIN phonebook_uploads pb
                ON pb.phone_hash = u.phone_number_hash
                AND pb.user_id   = ?
            WHERE u.phone_number_hash IN (
                SELECT unnest(?::text[])
            )
            AND u.user_id     != ?
            AND u.active      = true
            AND u.deleted_at IS NULL
            """;

        List<ContactMatch> matches = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(matchSql)) {

            stmt.setString(1, requestingUserId);
            stmt.setString(2, requestingUserId);
            stmt.setArray(3, conn.createArrayOf("text",
                    phoneHashes.toArray()));
            stmt.setString(4, requestingUserId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String contactUserId  = rs.getString("user_id");
                    String contactJid     = rs.getString("jid");
                    String displayName    = rs.getString("display_name");
                    String avatarUrl      = rs.getString("avatar_url");
                    String myLabel        = rs.getString("my_label_for_them");
                    boolean theyHaveMe    = rs.getBoolean("they_have_me");

                    // Determine subscription state
                    String subscription = theyHaveMe ? "both" : "to";

                    // Create roster entry for requesting user
                    createOrUpdateRosterEntry(
                            requestingUserId, contactUserId, contactJid,
                            myLabel, subscription
                    );

                    // If mutual, also create/update the reverse entry
                    if (theyHaveMe) {
                        upgradeReverseRosterEntry(
                                contactUserId, requestingUserId);
                    }

                    matches.add(new ContactMatch(
                            contactUserId,
                            contactJid,
                            displayName,
                            avatarUrl,
                            rs.getString("phone_number_hash"),
                            myLabel,
                            subscription
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("findMatchesAndCreateRoster error: "
                    + e.getMessage());
        }

        logger.info("Discovery: " + requestingUserId
                + " matched " + matches.size() + " contacts");
        return matches;
    }

    /**
     * Creates or updates a roster entry.
     * If entry exists with lower subscription, upgrades it.
     */
    private void createOrUpdateRosterEntry(String ownerUserId,
                                            String contactUserId,
                                            String contactJid,
                                            String nickname,
                                            String subscription) {
        String sql = """
            INSERT INTO roster_items (
                owner_user_id, contact_user_id, contact_jid,
                nickname, subscription, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (owner_user_id, contact_user_id) DO UPDATE SET
                contact_jid  = EXCLUDED.contact_jid,
                nickname     = EXCLUDED.nickname,
                subscription = CASE
                    WHEN roster_items.subscription = 'both' THEN 'both'
                    WHEN EXCLUDED.subscription    = 'both' THEN 'both'
                    ELSE EXCLUDED.subscription
                END,
                updated_at   = NOW()
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerUserId);
            stmt.setString(2, contactUserId);
            stmt.setString(3, contactJid);
            stmt.setString(4, nickname);
            stmt.setString(5, subscription);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("createOrUpdateRosterEntry error: "
                    + e.getMessage());
        }
    }

    /**
     * Upgrades the reverse roster entry to 'both' when mutual is detected.
     */
    private void upgradeReverseRosterEntry(String ownerUserId,
                                            String contactUserId) {
        String sql = """
            UPDATE roster_items
            SET subscription = 'both',
                updated_at   = NOW()
            WHERE owner_user_id   = ?
              AND contact_user_id = ?
              AND subscription   != 'both'
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerUserId);
            stmt.setString(2, contactUserId);
            int rows = stmt.executeUpdate();
            conn.commit();

            if (rows > 0) {
                logger.info("Mutual contact upgraded: "
                        + ownerUserId + " <-> " + contactUserId);
                // TODO: Push roster update to ownerUserId via XMPP
            }

        } catch (SQLException e) {
            logger.warning("upgradeReverseRosterEntry error: "
                    + e.getMessage());
        }
    }

    /**
     * Contact match record returned to the client.
     */
    public record ContactMatch(
            String userId,
            String jid,
            String displayName,
            String avatarUrl,
            String phoneHash,
            String contactName,
            String subscription
    ) {}
}