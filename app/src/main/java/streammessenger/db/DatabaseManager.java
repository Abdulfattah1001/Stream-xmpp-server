package streammessenger.db;


import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import streammessenger.roster.RosterItem;

public final class DatabaseManager {

    private static final Logger logger =
            Logger.getLogger(DatabaseManager.class.getName());

    private static final SecureRandom secureRandom = new SecureRandom();

    private final ConnectionPool pool;

    // The XMPP domain used to build JIDs
    private final String xmppDomain;

    public DatabaseManager(ConnectionPool pool, String xmppDomain) {
        this.pool       = pool;
        this.xmppDomain = xmppDomain;
    }

    // =========================================================================
    // User ID Generation
    // =========================================================================

    /**
     * Generates a unique opaque user ID.
     *
     * Format: u_ + 8 random hex chars
     * Example: u_7f3a9b2c
     *
     * 8 hex chars = 4 bytes = 32 bits of randomness
     * = 4,294,967,296 possible values
     *
     * We check for collisions and retry if needed.
     * At 10 million users the collision probability per generation
     * is still only 0.23% so retry is rarely needed.
     *
     * If you want more entropy (recommended for large scale):
     *   Change new byte[4] to new byte[8] for 16 hex chars
     */
    private String generateUserId() throws SQLException {
        int attempts = 0;

        while (attempts < 10) {
            // Generate 4 random bytes → 8 hex chars
            byte[] bytes = new byte[4];
            secureRandom.nextBytes(bytes);

            StringBuilder sb = new StringBuilder("u_");
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }

            String candidate = sb.toString(); // e.g. "u_7f3a9b2c"

            // Check for collision
            if (!userIdExists(candidate)) {
                return candidate;
            }

            attempts++;
            logger.warning("user_id collision on attempt "
                    + attempts + ": " + candidate);
        }

        throw new SQLException(
            "Failed to generate unique user_id after 10 attempts. " +
            "Consider increasing ID length.");
    }

    /**
     * Checks if a user_id already exists in the database.
     */
    private boolean userIdExists(String userId) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE user_id = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    // =========================================================================
    // User Registration
    // =========================================================================

    /**
     * Registers a new user after Firebase OTP verification.
     *
     * Called ONLY during sign up.
     * If the firebase_uid already exists (user re-registering),
     * we return the existing user record instead of creating a duplicate.
     *
     * @param firebaseUid   The uid from Firebase after OTP verification
     * @param phoneNumber   E.164 format: +2348012345678
     * @param displayName   Optional display name chosen by user
     * @return The user record (new or existing)
     */
    public UserRecord registerUser(String firebaseUid,
                                    String phoneNumber,
                                    String displayName) throws SQLException {

        // Check if this Firebase UID is already registered
        // (handles re-registration after app reinstall)
        UserRecord existing = getUserByFirebaseUid(firebaseUid);
        if (existing != null) {
            logger.info("Re-registration for existing user: "
                    + existing.userId());

            // Update display name if provided and changed
            if (displayName != null
                    && !displayName.equals(existing.displayName())) {
                updateDisplayName(existing.userId(), displayName);
            }

            return existing;
        }

        // Check if phone number is already registered under different UID
        // (handles phone number transfer / SIM swap)
        UserRecord byPhone = getUserByPhoneHash(hashPhone(phoneNumber));
        if (byPhone != null) {
            logger.warning("Phone number already registered: "
                    + "existing userId=" + byPhone.userId()
                    + " new firebaseUid=" + firebaseUid);

            // Update the firebase_uid (phone transferred to new account)
            updateFirebaseUid(byPhone.userId(), firebaseUid);
            return byPhone;
        }

        // Truly new user - generate ID and create record
        String userId      = generateUserId();
        String jid         = userId + "@" + xmppDomain;
        String phoneHash   = hashPhone(phoneNumber);
        String encryptedPhone = encryptPhone(phoneNumber);

        String sql = """
            INSERT INTO users (
                user_id,
                firebase_uid,
                phone_number,
                phone_number_hash,
                jid,
                display_name,
                active,
                phone_verified,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, true, true, NOW(), NOW())
            RETURNING id, user_id, jid, display_name, active
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, firebaseUid);
            stmt.setString(3, encryptedPhone);
            stmt.setString(4, phoneHash);
            stmt.setString(5, jid);
            stmt.setString(6, displayName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("INSERT returned no rows");
                }

                conn.commit();

                logger.info("New user registered: userId=" + userId
                        + " jid=" + jid);

                return new UserRecord(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("jid"),
                        rs.getString("display_name"),
                        rs.getBoolean("active")
                );
            }
        }
    }

    // =========================================================================
    // User Lookup
    // =========================================================================

    public UserRecord getUserByFirebaseUid(String firebaseUid) {
        String sql = """
            SELECT id, user_id, jid, display_name, active
            FROM users
            WHERE firebase_uid = ?
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, firebaseUid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapUserRecord(rs);
            }

        } catch (SQLException e) {
            logger.severe("getUserByFirebaseUid error: " + e.getMessage());
            return null;
        }
    }

    public Optional<String> getUserContactId(String uid){
        String sql = """
            SELECT contactId
            FROM users
            WHERE uid = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, uid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    logger.fine("Auth: unknown user=" + uid);
                    return Optional.empty();
                }

                return Optional.of(rs.getString("contactId"));
            }

        } catch (SQLException e) {
            logger.severe("DB error during authentication: " + e.getMessage());
            return Optional.empty();
        }
    }

    public UserRecord getUserByUserId(String userId) {
        String sql = """
            SELECT id, user_id, jid, display_name, active
            FROM users
            WHERE user_id = ?
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapUserRecord(rs);
            }

        } catch (SQLException e) {
            logger.severe("getUserByUserId error: " + e.getMessage());
            return null;
        }
    }

    private UserRecord getUserByPhoneHash(String phoneHash) {
        String sql = """
            SELECT id, user_id, jid, display_name, active
            FROM users
            WHERE phone_number_hash = ?
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, phoneHash);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapUserRecord(rs);
            }

        } catch (SQLException e) {
            logger.severe("getUserByPhoneHash error: " + e.getMessage());
            return null;
        }
    }

    public boolean contactExists(String jid) {
        String sql = """
            SELECT 1 FROM users
            WHERE jid = ?
              AND active = true
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, jid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            logger.severe("contactExists error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Session Tokens
    // =========================================================================

    /**
     * Stores a new session token in the database.
     * Token is stored as SHA-256 hash - raw token never hits the DB.
     *
     * expires_at is set to NULL intentionally.
     * Tokens do not expire on their own.
     * They are only invalidated by explicit revocation.
     */
    public void storeSessionToken(String userId,
                                   String tokenHash,
                                   String deviceLabel,
                                   String pushToken,
                                   String platform,
                                   String appVersion,
                                   Instant expiresAt, // ignored - kept for API compat
                                   String ipAddress) {
        String sql = """
            INSERT INTO session_tokens (
                user_id,
                token_hash,
                device_label,
                push_token,
                platform,
                app_version,
                expires_at,
                created_from_ip,
                created_at,
                last_used_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?::inet, NOW(), NOW())
            """;
        // expires_at = NULL means "never expires until revoked"

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, tokenHash);
            stmt.setString(3, deviceLabel);
            stmt.setString(4, pushToken);
            stmt.setString(5, platform);
            stmt.setString(6, appVersion);
            stmt.setString(7, ipAddress);

            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("storeSessionToken error: " + e.getMessage());
        }
    }

    /**
     * Looks up a session token by its hash.
     * Called during XMPP login and HTTP API validation.
     */
    public SessionTokenRecord getSessionToken(String tokenHash) {
        String sql = """
            SELECT
                st.user_id,
                st.push_token,
                st.platform,
                st.revoked_at,
                st.last_used_at,
                st.device_label
            FROM session_tokens st
            INNER JOIN users u ON u.user_id = st.user_id
            WHERE st.token_hash = ?
              AND u.active = true
              AND u.deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, tokenHash);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;

                Timestamp revokedAt = rs.getTimestamp("revoked_at");

                return new SessionTokenRecord(
                        rs.getString("user_id"),
                        rs.getString("push_token"),
                        rs.getString("platform"),
                        revokedAt != null ? revokedAt.toInstant() : null,
                        null, // expires_at is always null (no expiry)
                        rs.getString("device_label")
                );
            }

        } catch (SQLException e) {
            logger.severe("getSessionToken error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Updates last_used_at for a session token.
     * Called on every successful XMPP login.
     */
    public void touchSessionToken(String tokenHash) {
        String sql = """
            UPDATE session_tokens
            SET last_used_at = NOW()
            WHERE token_hash = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, tokenHash);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("touchSessionToken error: " + e.getMessage());
        }
    }

    /**
     * Revokes a single session token (logout from this device).
     */
    public void revokeSessionToken(String rawToken, String reason) {
        String tokenHash = hashToken(rawToken);
        String sql = """
            UPDATE session_tokens
            SET revoked_at     = NOW(),
                revoked_reason = ?
            WHERE token_hash = ?
              AND revoked_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, reason);
            stmt.setString(2, tokenHash);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("revokeSessionToken error: " + e.getMessage());
        }
    }

    /**
     * Revokes ALL session tokens for a user (logout from all devices).
     */
    public void revokeAllSessionTokens(String userId, String reason) {
        String sql = """
            UPDATE session_tokens
            SET revoked_at     = NOW(),
                revoked_reason = ?
            WHERE user_id   = ?
              AND revoked_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, reason);
            stmt.setString(2, userId);
            int rows = stmt.executeUpdate();
            conn.commit();

            logger.info("Revoked " + rows
                    + " session tokens for userId=" + userId);

        } catch (SQLException e) {
            logger.severe("revokeAllSessionTokens error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Contact Discovery
    // =========================================================================

    /**
     * Finds which hashed phone numbers belong to registered users.
     * Does NOT return the requester's own entry.
     */
    public List<ContactMatch> discoverContacts(List<String> phoneHashes,
                                                String requestingUserId) {
        if (phoneHashes.isEmpty()) return new ArrayList<>();

        // Build parameterized IN clause
        String placeholders = "?,".repeat(phoneHashes.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);

        String sql = String.format("""
            SELECT
                u.phone_number_hash,
                u.jid,
                u.display_name
            FROM users u
            WHERE u.phone_number_hash IN (%s)
              AND u.user_id != ?
              AND u.active = true
              AND u.deleted_at IS NULL
            """, placeholders);

        List<ContactMatch> matches = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int paramIdx = 1;
            for (String hash : phoneHashes) {
                stmt.setString(paramIdx++, hash);
            }
            stmt.setString(paramIdx, requestingUserId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    matches.add(new ContactMatch(
                            rs.getString("phone_number_hash"),
                            rs.getString("jid"),
                            rs.getString("display_name")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("discoverContacts error: " + e.getMessage());
        }

        return matches;
    }

    // =========================================================================
    // Updates
    // =========================================================================

    public void updateLastSeen(String userId) {
        String sql = """
            UPDATE users
            SET last_seen  = NOW(),
                updated_at = NOW()
            WHERE user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updateLastSeen error: " + e.getMessage());
        }
    }

    public void updateDisplayName(String userId, String displayName) {
        String sql = """
            UPDATE users
            SET display_name = ?,
                updated_at   = NOW()
            WHERE user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, displayName);
            stmt.setString(2, userId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updateDisplayName error: " + e.getMessage());
        }
    }

    public void updatePushToken(String userId,
                                 String pushToken,
                                 String platform) {
        // Update the most recently used session token for this user
        String sql = """
            UPDATE session_tokens
            SET push_token   = ?,
                platform     = ?
            WHERE user_id    = ?
              AND revoked_at IS NULL
              AND last_used_at = (
                  SELECT MAX(last_used_at)
                  FROM session_tokens
                  WHERE user_id   = ?
                    AND revoked_at IS NULL
              )
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pushToken);
            stmt.setString(2, platform);
            stmt.setString(3, userId);
            stmt.setString(4, userId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updatePushToken error: " + e.getMessage());
        }
    }

    private void updateFirebaseUid(String userId, String newFirebaseUid) {
        String sql = """
            UPDATE users
            SET firebase_uid = ?,
                updated_at   = NOW()
            WHERE user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newFirebaseUid);
            stmt.setString(2, userId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("updateFirebaseUid error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Audit Log
    // =========================================================================

    public void insertAuditLog(String eventType,
                                String userId,
                                String details,
                                String ipAddress) {
        String sql = """
            INSERT INTO audit_log (event_type, user_id, details, ip_address, created_at)
            VALUES (?, ?, ?::jsonb, ?::inet, NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, eventType);
            stmt.setString(2, userId);
            stmt.setString(3, details);
            stmt.setString(4, ipAddress);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("insertAuditLog error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Existing methods (offline messages, roster, etc.)
    // kept from previous implementation
    // =========================================================================

    public Optional<String> authenticateUser(String userId, String token) {
        // Delegated to SessionTokenService - kept for interface compat
        return Optional.empty();
    }

    public boolean storeOfflineMessage(String fromJid, String toContactId,
                                        String body, String stanzaId) {
        String sql = """
            INSERT INTO offline_messages
                (from_jid, to_user_id, to_jid, stanza_id,
                 stanza_xml, body_preview, created_at)
            SELECT ?, u.user_id, ?, ?, ?, ?, NOW()
            FROM users u
            WHERE u.jid = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fromJid);
            stmt.setString(2, toContactId);
            stmt.setString(3, stanzaId);
            stmt.setString(4, body); // stanza_xml - simplified
            stmt.setString(5, body.length() > 200
                    ? body.substring(0, 200) : body);
            stmt.setString(6, toContactId);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("storeOfflineMessage error: " + e.getMessage());
            return false;
        }
    }

    public List<OfflineMessage> fetchOfflineMessages(String contactJid) {
        String sql = """
            DELETE FROM offline_messages
            WHERE to_jid = ?
            RETURNING from_jid, stanza_xml, stanza_id, created_at
            ORDER BY created_at ASC
            """;

        List<OfflineMessage> messages = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, contactJid);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new OfflineMessage(
                            rs.getString("from_jid"),
                            rs.getString("stanza_xml"),
                            rs.getString("stanza_id"),
                            rs.getTimestamp("created_at")
                    ));
                }
            }
            conn.commit();

        } catch (SQLException e) {
            logger.severe("fetchOfflineMessages error: " + e.getMessage());
        }

        return messages;
    }

    public List<RosterItem> getRosterItems(String ownerJid) {
        String sql = """
            SELECT
                u.jid           AS contact_jid,
                ri.nickname     AS name,
                ri.subscription,
                ri.ask,
                COALESCE(
                    ARRAY_AGG(rg.group_name ORDER BY rg.group_name)
                    FILTER (WHERE rg.group_name IS NOT NULL),
                    '{}'
                ) AS groups
            FROM roster_items ri
            INNER JOIN users owner ON owner.jid = ?
            LEFT  JOIN users u     ON u.user_id = ri.contact_user_id
            LEFT  JOIN roster_groups rg ON rg.roster_item_id = ri.id
            WHERE ri.owner_user_id = owner.user_id
              AND ri.blocked = false
            GROUP BY u.jid, ri.nickname,
                     ri.subscription, ri.ask
            ORDER BY u.jid
            """;

        List<RosterItem> items = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerJid);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    java.sql.Array arr = rs.getArray("groups");
                    List<String> groups = new ArrayList<>();
                    if (arr != null) {
                        for (String g : (String[]) arr.getArray()) {
                            if (g != null) groups.add(g);
                        }
                    }

                    items.add(new RosterItem(
                            rs.getString("contact_jid"),
                            rs.getString("name"),
                            rs.getString("subscription"),
                            rs.getString("ask"),
                            groups
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("getRosterItems error: " + e.getMessage());
        }

        return items;
    }

    public RosterItem getRosterItem(String ownerJid, String contactJid) {
        String sql = """
            SELECT
                u.jid           AS contact_jid,
                ri.nickname     AS name,
                ri.subscription,
                ri.ask
            FROM roster_items ri
            INNER JOIN users owner   ON owner.jid = ?
            INNER JOIN users contact ON contact.jid = ?
            WHERE ri.owner_user_id   = owner.user_id
              AND ri.contact_user_id = contact.user_id
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerJid);
            stmt.setString(2, contactJid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new RosterItem(
                        rs.getString("contact_jid"),
                        rs.getString("name"),
                        rs.getString("subscription"),
                        rs.getString("ask"),
                        new ArrayList<>()
                );
            }

        } catch (SQLException e) {
            logger.severe("getRosterItem error: " + e.getMessage());
            return null;
        }
    }

    public boolean upsertRosterItem(String ownerJid, RosterItem item) {
        String sql = """
            INSERT INTO roster_items (
                owner_user_id,
                contact_user_id,
                contact_jid,
                nickname,
                subscription,
                ask,
                updated_at
            )
            SELECT
                owner.user_id,
                contact.user_id,
                contact.jid,
                ?,
                ?,
                ?,
                NOW()
            FROM users owner
            CROSS JOIN users contact
            WHERE owner.jid   = ?
              AND contact.jid = ?
            ON CONFLICT (owner_user_id, contact_user_id)
            DO UPDATE SET
                nickname     = EXCLUDED.nickname,
                subscription = EXCLUDED.subscription,
                ask          = EXCLUDED.ask,
                updated_at   = NOW()
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, item.name());
            stmt.setString(2, item.subscription());
            stmt.setString(3, item.ask());
            stmt.setString(4, ownerJid);
            stmt.setString(5, item.jid());
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("upsertRosterItem error: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteRosterItem(String ownerJid, String contactJid) {
        String sql = """
            DELETE FROM roster_items
            WHERE owner_user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            AND contact_user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerJid);
            stmt.setString(2, contactJid);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("deleteRosterItem error: " + e.getMessage());
            return false;
        }
    }

    public boolean storePendingSubscription(String fromJid,
                                             String toJid,
                                             String type) {
        String sql = """
            INSERT INTO subscription_requests (
                from_user_id, from_jid,
                to_user_id,   type,
                created_at
            )
            SELECT
                f.user_id, ?,
                t.user_id, ?,
                NOW()
            FROM users f
            CROSS JOIN users t
            WHERE f.jid = ? AND t.jid = ?
            ON CONFLICT (from_user_id, to_user_id, type) DO NOTHING
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fromJid);
            stmt.setString(2, type);
            stmt.setString(3, fromJid);
            stmt.setString(4, toJid);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("storePendingSubscription error: " + e.getMessage());
            return false;
        }
    }

    public List<PendingSubscription> fetchPendingSubscriptions(String toJid) {
        String sql = """
            DELETE FROM subscription_requests
            WHERE to_user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            RETURNING from_jid, type, created_at
            """;

        List<PendingSubscription> result = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, toJid);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new PendingSubscription(
                            rs.getString("from_jid"),
                            rs.getString("type"),
                            rs.getTimestamp("created_at")
                    ));
                }
            }
            conn.commit();

        } catch (SQLException e) {
            logger.severe("fetchPendingSubscriptions error: " + e.getMessage());
        }

        return result;
    }

    public boolean deletePendingSubscription(String fromJid, String toJid) {
        String sql = """
            DELETE FROM subscription_requests
            WHERE from_user_id = (SELECT user_id FROM users WHERE jid = ?)
              AND to_user_id   = (SELECT user_id FROM users WHERE jid = ?)
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fromJid);
            stmt.setString(2, toJid);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("deletePendingSubscription error: " + e.getMessage());
            return false;
        }
    }

    public void extendSessionToken(String tokenHash, Instant newExpiry) {
        // No-op - tokens don't expire
        // Kept for interface compatibility
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * SHA-256 hash of a phone number.
     * Used for contact discovery without exposing raw numbers.
     */
    public static String hashPhone(String phoneNumber) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                phoneNumber.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * SHA-256 hash of a session token.
     * This is what we store - raw token is never in the DB.
     */
    public static String hashToken(String rawToken) {
        return hashPhone(rawToken); // Same SHA-256 logic
    }

    /**
     * Encrypts a phone number for storage.
     *
     * In production: use AES-256-GCM with a key from
     * an HSM or KMS (AWS KMS, Google Cloud KMS).
     *
     * Here: simplified placeholder - replace with real encryption.
     * The phone hash is used for lookups, the encrypted value
     * is only decrypted when absolutely necessary (account recovery).
     */
    private String encryptPhone(String phoneNumber) {
        // TODO: Replace with AES-256-GCM encryption
        // For now: store as-is (not safe for production)
        // Real implementation:
        //   byte[] iv = new byte[12];
        //   secureRandom.nextBytes(iv);
        //   Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        //   cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        //   byte[] encrypted = cipher.doFinal(phoneNumber.getBytes(UTF_8));
        //   return Base64.encode(iv) + ":" + Base64.encode(encrypted);
        return phoneNumber;
    }

    private UserRecord mapUserRecord(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("jid"),
                rs.getString("display_name"),
                rs.getBoolean("active")
        );
    }

    // =========================================================================
    // Value objects
    // =========================================================================

    /**
     * id       → PostgreSQL auto-incremented BIGSERIAL
     *            Generated by Postgres, never by your code
     *            Used only for internal DB joins
     *
     * user_id  → "u_7f3a9b2c" generated by generateUserId()
     *            Used externally in JIDs, API responses, etc.
     */
    public record UserRecord(
            long id,           // BIGSERIAL from Postgres - auto generated
            String userId,     // "u_7f3a9b2c" - generated by generateUserId()
            String jid,        // "u_7f3a9b2c@yourdomain.com"
            String displayName,
            boolean active
    ) {}

    public record SessionTokenRecord(
            String userId,
            String pushToken,
            String platform,
            Instant revokedAt,  // null = not revoked
            Instant expiresAt,  // null = never expires
            String deviceLabel
    ) {}

    public record OfflineMessage(
            String fromJid,
            String body,
            String stanzaId,
            Timestamp createdAt
    ) {}

    public record PendingSubscription(
            String fromJid,
            String type,
            Timestamp createdAt
    ) {}

    public record ContactMatch(
            String phoneHash,
            String jid,
            String displayName
    ) {}


    // =========================================================================
    // Status Methods
    // =========================================================================

    /**
     * Publishes a new status. Returns the status_id UUID or null on failure.
     */
    public String publishStatus(String userId,
                                String type,
                                String textContent,
                                String backgroundColor,
                                int fontStyle,
                                String mediaStorageKey,
                                String caption,
                                String mimeType,
                                int durationSeconds,
                                long fileSizeBytes,
                                String visibility) {
        String sql = """
            INSERT INTO user_status (
                user_id, status_type, text_content,
                background_color, font_style,
                media_storage_key, caption, mime_type,
                duration_seconds, file_size_bytes,
                visibility, created_at,
                expires_at
            ) VALUES (
                ?, ?, ?,
                ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, NOW(),
                NOW() + INTERVAL '24 hours'
            )
            RETURNING status_id::text
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, type);
            stmt.setString(3, textContent);
            stmt.setString(4, backgroundColor);
            stmt.setInt(5, fontStyle);
            stmt.setString(6, mediaStorageKey);
            stmt.setString(7, caption);
            stmt.setString(8, mimeType);
            stmt.setInt(9, durationSeconds);
            stmt.setLong(10, fileSizeBytes);
            stmt.setString(11, visibility != null ? visibility : "all_contacts");

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                conn.commit();
                return rs.getString(1);
            }

        } catch (SQLException e) {
            logger.severe("publishStatus error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns all active statuses from contacts of this user.
     * Respects visibility settings and 24-hour expiry.
     */
    public List<StatusRecord> fetchContactStatuses(String userId) {
        String sql = """
            SELECT
                s.status_id::text,
                s.status_type,
                u.jid           AS owner_jid,
                s.text_content,
                s.background_color,
                s.font_style,
                s.media_storage_key,
                s.caption,
                s.mime_type,
                s.duration_seconds,
                s.file_size_bytes,
                s.view_count,
                s.created_at::text,
                s.expires_at::text,
                EXISTS(
                    SELECT 1 FROM status_views sv
                    WHERE sv.status_id      = s.status_id
                      AND sv.viewer_user_id = ?
                ) AS viewed_by_user
            FROM user_status s
            INNER JOIN users u
                ON u.user_id = s.user_id
            INNER JOIN roster_items ri
                ON ri.contact_user_id = s.user_id
               AND ri.owner_user_id   = ?
               AND ri.subscription IN ('to', 'both')
               AND ri.blocked = false
            WHERE s.expires_at  > NOW()
              AND s.deleted_at IS NULL
              AND (
                s.visibility = 'all_contacts'
                OR (
                    s.visibility = 'selected'
                    AND EXISTS (
                        SELECT 1 FROM status_visibility_list svl
                        WHERE svl.status_id  = s.status_id
                          AND svl.user_id    = ?
                          AND svl.list_type  = 'include'
                    )
                )
                OR (
                    s.visibility = 'except'
                    AND NOT EXISTS (
                        SELECT 1 FROM status_visibility_list svl
                        WHERE svl.status_id  = s.status_id
                          AND svl.user_id    = ?
                          AND svl.list_type  = 'exclude'
                    )
                )
              )
            ORDER BY s.created_at DESC
            """;

        List<StatusRecord> statuses = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId); // viewed_by_user check
            stmt.setString(2, userId); // roster join
            stmt.setString(3, userId); // selected visibility
            stmt.setString(4, userId); // except visibility

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    statuses.add(new StatusRecord(
                            rs.getString("status_id"),
                            rs.getString("status_type"),
                            rs.getString("owner_jid"),
                            rs.getString("text_content"),
                            rs.getString("background_color"),
                            rs.getInt("font_style"),
                            rs.getString("media_storage_key"),
                            rs.getString("caption"),
                            rs.getString("mime_type"),
                            rs.getInt("duration_seconds"),
                            rs.getLong("file_size_bytes"),
                            rs.getInt("view_count"),
                            rs.getString("created_at"),
                            rs.getString("expires_at"),
                            rs.getBoolean("viewed_by_user")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("fetchContactStatuses error: " + e.getMessage());
        }

        return statuses;
    }

    /**
     * Soft-deletes a status.
     * Only the owner can delete their own status.
     */
    public boolean deleteStatus(String statusId, String userId) {
        String sql = """
            UPDATE user_status
            SET deleted_at = NOW()
            WHERE status_id::text = ?
              AND user_id         = ?
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, statusId);
            stmt.setString(2, userId);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("deleteStatus error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Records that a user viewed a status.
     * Also increments the view count on the status.
     * Idempotent - viewing multiple times only counts once.
     */
    public void recordStatusView(String statusId, String viewerUserId) {
        String insertView = """
            INSERT INTO status_views (status_id, viewer_user_id, viewed_at)
            SELECT s.status_id, ?, NOW()
            FROM user_status s
            WHERE s.status_id::text = ?
              AND s.user_id != ?
              AND s.expires_at > NOW()
              AND s.deleted_at IS NULL
            ON CONFLICT (status_id, viewer_user_id) DO NOTHING
            """;

        String updateCount = """
            UPDATE user_status
            SET view_count = (
                SELECT COUNT(*) FROM status_views
                WHERE status_id = user_status.status_id
            )
            WHERE status_id::text = ?
            """;

        try (Connection conn = pool.getConnection()) {

            try (PreparedStatement stmt = conn.prepareStatement(insertView)) {
                stmt.setString(1, viewerUserId);
                stmt.setString(2, statusId);
                stmt.setString(3, viewerUserId);
                stmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement(updateCount)) {
                stmt.setString(1, statusId);
                stmt.executeUpdate();
            }

            conn.commit();

        } catch (SQLException e) {
            logger.warning("recordStatusView error: " + e.getMessage());
        }
    }
    /**
     * Returns JIDs of contacts who can see this user's status.
     * Used to broadcast status notifications to online contacts.
     */
    public List<String> getStatusVisibleContactJids(String userId) {
        String sql = """
            SELECT u.jid
            FROM roster_items ri
            INNER JOIN users u
                ON u.user_id = ri.owner_user_id
            WHERE ri.contact_user_id = ?
              AND ri.subscription IN ('from', 'both')
              AND ri.blocked = false
              AND u.active = true
              AND u.deleted_at IS NULL
            """;

        List<String> jids = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    jids.add(rs.getString("jid"));
                }
            }

        } catch (SQLException e) {
            logger.severe("getStatusVisibleContactJids error: " + e.getMessage());
        }

        return jids;
    }

    // =========================================================================
    // Message Methods (updated for encryption)
    // =========================================================================

    /**
     * Stores an encrypted offline message.
     *
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
    public boolean storeEncryptedMessage(String fromJid,
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
        String sql = """
            INSERT INTO messages (
                message_id,
                from_user_id,
                to_user_id,
                message_type,
                encrypted_content,
                iv,
                media_storage_key,
                encrypted_metadata,
                mime_type,
                file_size_bytes,
                reply_to_message_id,
                status,
                was_offline,
                created_at,
                expires_at
            )
            SELECT
                ?::uuid,
                f.user_id,
                t.user_id,
                ?, ?, ?, ?, ?, ?, ?, ?::uuid,
                'pending',
                true,
                NOW(),
                NOW() + INTERVAL '30 days'
            FROM users f
            CROSS JOIN users t
            WHERE f.jid = ?
              AND t.jid = ?
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

    /**
     * Fetches and deletes all pending offline messages for a user.
     *
     * Uses DELETE ... RETURNING for atomic fetch-and-delete.
     * If server crashes after DELETE but before delivery:
     * messages are lost. XEP-0198 Stream Management handles this
     * at the XMPP layer (client will request retransmission).
     *
     * Messages are ordered by created_at ASC so older messages
     * are delivered first (correct chronological order).
     */
    public List<EncryptedOfflineMessage> fetchEncryptedOfflineMessages(
            String toJid) {

        String sql = """
            DELETE FROM messages
            WHERE to_user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            AND status = 'pending'
            RETURNING
                message_id::text,
                (SELECT jid FROM users WHERE user_id = from_user_id) AS from_jid,
                message_type,
                encrypted_content,
                iv,
                media_storage_key,
                encrypted_metadata,
                mime_type,
                file_size_bytes,
                (SELECT message_id::text FROM messages m2
                 WHERE m2.id = messages.reply_to_message_id) AS reply_to_id,
                created_at
            ORDER BY created_at ASC
            """;

        List<EncryptedOfflineMessage> messages = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, toJid);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new EncryptedOfflineMessage(
                            rs.getString("message_id"),
                            rs.getString("from_jid"),
                            rs.getString("message_type"),
                            rs.getString("encrypted_content"),
                            rs.getString("iv"),
                            rs.getString("media_storage_key"),
                            rs.getString("encrypted_metadata"),
                            rs.getString("mime_type"),
                            rs.getLong("file_size_bytes"),
                            rs.getString("reply_to_id"),
                            rs.getTimestamp("created_at")
                    ));
                }
            }
            conn.commit();

        } catch (SQLException e) {
            logger.severe("fetchEncryptedOfflineMessages error: "
                    + e.getMessage());
        }

        return messages;
    }

    /**
     * Marks a message as delivered.
     * Called when the recipient's session acknowledges receipt.
     */
    public void markMessageDelivered(String messageId) {
        String sql = """
            UPDATE messages
            SET status       = 'delivered',
                delivered_at = NOW()
            WHERE message_id::text = ?
              AND status     = 'pending'
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("markMessageDelivered error: " + e.getMessage());
        }
    }

    /**
     * Marks a message as read.
     * Called when the recipient opens the chat and reads the message.
     */
    public void markMessageRead(String messageId) {
        String sql = """
            UPDATE messages
            SET status  = 'read',
                read_at = NOW()
            WHERE message_id::text = ?
              AND status IN ('pending', 'delivered')
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("markMessageRead error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Public Key Methods (for E2E encryption key exchange)
    // =========================================================================

    /**
     * Stores a user's public identity key and signed pre-key.
     * Called once after registration when the client generates its keys.
     */
    public boolean storeUserKeys(String userId,
                                 String identityKey,
                                 String signedPreKey,
                                 int signedPreKeyId,
                                 int keyVersion) {
        String sql = """
            INSERT INTO user_keys (
                user_id,
                identity_key,
                signed_pre_key,
                signed_pre_key_id,
                key_version,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (user_id, key_version)
            DO UPDATE SET
                identity_key      = EXCLUDED.identity_key,
                signed_pre_key    = EXCLUDED.signed_pre_key,
                signed_pre_key_id = EXCLUDED.signed_pre_key_id,
                updated_at        = NOW()
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, identityKey);
            stmt.setString(3, signedPreKey);
            stmt.setInt(4, signedPreKeyId);
            stmt.setInt(5, keyVersion);

            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("storeUserKeys error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Uploads a batch of one-time pre-keys for a user.
     * Called by the client periodically to replenish the key supply.
     *
     * One-time pre-keys are consumed one per new conversation.
     * When supply runs low (< 10 remaining), the client uploads more.
     */
    public int storeOneTimePreKeys(String userId,
                                   List<PreKey> preKeys) {
        String sql = """
            INSERT INTO one_time_pre_keys (
                user_id, key_id, public_key, created_at
            ) VALUES (?, ?, ?, NOW())
            ON CONFLICT (user_id, key_id) DO NOTHING
            """;

        int stored = 0;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (PreKey key : preKeys) {
                stmt.setString(1, userId);
                stmt.setInt(2, key.keyId());
                stmt.setString(3, key.publicKey());
                stmt.addBatch();
                stored++;
            }

            stmt.executeBatch();
            conn.commit();

            logger.info("Stored " + stored
                    + " one-time pre-keys for userId=" + userId);

        } catch (SQLException e) {
            logger.severe("storeOneTimePreKeys error: " + e.getMessage());
        }

        return stored;
    }

    /**
     * Fetches a user's public keys for initiating an encrypted session.
     *
     * Returns:
     *   - Identity key (long-term, always the same)
     *   - Signed pre-key (rotated periodically)
     *   - One one-time pre-key (claimed and deleted from supply)
     *
     * The one-time pre-key provides forward secrecy:
     * even if long-term keys are compromised later,
     * past messages cannot be decrypted.
     */
    public UserPublicKeys fetchPublicKeysForUser(String targetUserId,
                                                 String requestingUserId) {
        // Get identity and signed pre-key
        String keysSql = """
            SELECT identity_key, signed_pre_key, signed_pre_key_id, key_version
            FROM user_keys
            WHERE user_id = ?
            ORDER BY key_version DESC
            LIMIT 1
            """;

        // Claim one one-time pre-key (atomically mark as claimed)
        String otpkSql = """
            UPDATE one_time_pre_keys
            SET claimed_at         = NOW(),
                claimed_by_user_id = ?
            WHERE id = (
                SELECT id FROM one_time_pre_keys
                WHERE user_id    = ?
                  AND claimed_at IS NULL
                ORDER BY key_id ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING key_id, public_key
            """;

        try (Connection conn = pool.getConnection()) {

            String identityKey    = null;
            String signedPreKey   = null;
            int    signedPreKeyId = 0;

            try (PreparedStatement stmt = conn.prepareStatement(keysSql)) {
                stmt.setString(1, targetUserId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        identityKey    = rs.getString("identity_key");
                        signedPreKey   = rs.getString("signed_pre_key");
                        signedPreKeyId = rs.getInt("signed_pre_key_id");
                    }
                }
            }

            if (identityKey == null) {
                logger.warning("No keys found for userId=" + targetUserId);
                return null;
            }

            // Claim a one-time pre-key
            String otpkPublicKey = null;
            int    otpkKeyId     = 0;

            try (PreparedStatement stmt = conn.prepareStatement(otpkSql)) {
                stmt.setString(1, requestingUserId);
                stmt.setString(2, targetUserId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        otpkKeyId     = rs.getInt("key_id");
                        otpkPublicKey = rs.getString("public_key");
                    }
                }
            }

            conn.commit();

            // Check remaining pre-key count
            int remaining = countRemainingPreKeys(targetUserId);
            if (remaining < 10) {
                logger.warning("Low pre-key supply for userId="
                        + targetUserId + " remaining=" + remaining);
                // TODO: Send push notification to client to upload more keys
            }

            return new UserPublicKeys(
                    targetUserId,
                    identityKey,
                    signedPreKey,
                    signedPreKeyId,
                    otpkPublicKey,
                    otpkKeyId
            );

        } catch (SQLException e) {
            logger.severe("fetchPublicKeysForUser error: " + e.getMessage());
            return null;
        }
    }

    private int countRemainingPreKeys(String userId) {
        String sql = """
            SELECT COUNT(*) FROM one_time_pre_keys
            WHERE user_id = ? AND claimed_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        } catch (SQLException e) {
            return 0;
        }
    }

    // =========================================================================
    // Push Token Operations
    // =========================================================================

    /**
     * Retrieves the most recently used push token for a user.
     * Used by PushNotificationService to deliver notifications
     * when the user has multiple devices registered.
     *
     * Returns the most recent active device's push token since
     * that's likely the user's primary device.
     */
    public PushTarget getPushTarget(String userId) {
        String sql = """
            SELECT
                st.push_token,
                st.platform,
                st.device_label,
                u.display_name
            FROM session_tokens st
            INNER JOIN users u ON u.user_id = st.user_id
            WHERE st.user_id    = ?
              AND st.revoked_at IS NULL
              AND st.push_token IS NOT NULL
              AND u.active = true
              AND u.deleted_at IS NULL
            ORDER BY st.last_used_at DESC
            LIMIT 1
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new PushTarget(
                        userId,
                        rs.getString("push_token"),
                        rs.getString("platform"),
                        rs.getString("device_label"),
                        rs.getString("display_name")
                );
            }

        } catch (SQLException e) {
            logger.severe("getPushTarget error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns push targets for ALL devices of a user.
     * Used when we want to notify every device (e.g. incoming call).
     */
    public List<PushTarget> getAllPushTargetsForUser(String userId) {
        String sql = """
            SELECT
                st.push_token,
                st.platform,
                st.device_label,
                u.display_name
            FROM session_tokens st
            INNER JOIN users u ON u.user_id = st.user_id
            WHERE st.user_id    = ?
              AND st.revoked_at IS NULL
              AND st.push_token IS NOT NULL
              AND u.active = true
            ORDER BY st.last_used_at DESC
            """;

        List<PushTarget> targets = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    targets.add(new PushTarget(
                            userId,
                            rs.getString("push_token"),
                            rs.getString("platform"),
                            rs.getString("device_label"),
                            rs.getString("display_name")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("getAllPushTargetsForUser error: " + e.getMessage());
        }

        return targets;
    }

    /**
     * Clears an invalid push token when FCM/APNs reports it as expired.
     * Prevents repeatedly sending to a dead token.
     */
    public void clearPushToken(String userId, String invalidToken) {
        String sql = """
            UPDATE session_tokens
            SET push_token = NULL
            WHERE user_id   = ?
              AND push_token = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, invalidToken);
            stmt.executeUpdate();
            conn.commit();

            logger.info("Cleared invalid push token for userId=" + userId);

        } catch (SQLException e) {
            logger.warning("clearPushToken error: " + e.getMessage());
        }
    }

    /**
     * Logs a push notification delivery attempt.
     * Used for auditing, debugging, and rate limiting.
     */
    public void logPushNotification(String userId,
                                    String pushToken,
                                    String platform,
                                    String notificationType,
                                    String referenceId,
                                    boolean success,
                                    String errorCode,
                                    String idempotencyKey) {
        String sql = """
            INSERT INTO push_notifications_log (
                to_user_id, push_token, platform,
                notification_type, reference_id,
                sent_at, success, error_code,
                idempotency_key
            ) VALUES (?, ?, ?, ?, ?, NOW(), ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, pushToken);
            stmt.setString(3, platform);
            stmt.setString(4, notificationType);
            stmt.setString(5, referenceId);
            stmt.setBoolean(6, success);
            stmt.setString(7, errorCode);
            stmt.setString(8, idempotencyKey);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("logPushNotification error: " + e.getMessage());
        }
    }

    /**
     * Gets the JID for a user_id lookup.
     * Used by bot/group handlers.
     */
    public String getJidByUserId(String userId) {
        String sql = """
            SELECT jid FROM users
            WHERE user_id = ? AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("jid") : null;
            }

        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Represents a push notification delivery target.
     * One user can have multiple targets (one per device).
     */
    public record PushTarget(
            String userId,
            String pushToken,     // FCM token for Android, APNs token for iOS
            String platform,       // "android" | "ios" | "web"
            String deviceLabel,    // Human-readable device name
            String displayName     // User's display name (for notification)
    ) {}

    // =========================================================================
    // Value objects
    // =========================================================================

    public record StatusRecord(
            String statusId,
            String type,           // text | image | video
            String ownerJid,
            String textContent,
            String backgroundColor,
            int fontStyle,
            String mediaStorageKey,
            String caption,
            String mimeType,
            int durationSeconds,
            long fileSizeBytes,
            int viewCount,
            String createdAt,
            String expiresAt,
            boolean viewedByUser
    ) {}

    public record EncryptedOfflineMessage(
            String messageId,
            String fromJid,
            String messageType,       // text | image | video | audio | file
            String encryptedContent,  // Base64 AES-256-GCM ciphertext
            String iv,                // Base64 12-byte IV
            String mediaStorageKey,   // Object storage path (null for text)
            String encryptedMetadata, // Encrypted dimensions/duration etc.
            String mimeType,          // MIME type hint (not sensitive)
            long fileSizeBytes,       // File size hint (not sensitive)
            String replyToId,         // UUID of replied-to message (null)
            java.sql.Timestamp createdAt
    ) {}

    public record UserPublicKeys(
            String userId,
            String identityKey,      // Base64 EC public key
            String signedPreKey,     // Base64 signed EC public key
            int signedPreKeyId,
            String oneTimePreKey,    // Base64 one-time EC public key (may be null)
            int oneTimePreKeyId
    ) {}

    public record PreKey(
            int keyId,
            String publicKey         // Base64 encoded EC public key
    ) {}
}