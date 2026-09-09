package streammessenger.db;


import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import streammessenger.api.GroupController;
import streammessenger.group.repository.GroupRepository;
import streammessenger.roster.RosterItem;
import streammessenger.stanza.PrivacyHandler;

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
     * <p>
     * Format: u_ + 8 random hex chars
     * Example: u_7f3a9b2c
     * <p>
     * 8 hex chars = 4 bytes = 32 bits of randomness
     * = 4,294,967,296 possible values
     * <p>
     * We check for collisions and retry if needed.
     * At 10 million users the collision probability per generation
     * is still only 0.23% so retry is rarely needed.
     * <p>
     * If later want more entropy (recommended for large scale):
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
            logger.warning("user_id collision on attempt " + attempts + ": " + candidate);
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
     * <p>
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
            logger.info("Re-registration for existing user: " + existing.userId());

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
        """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, userId);
            stmt.setString(2, firebaseUid);
            stmt.setString(3, encryptedPhone);
            stmt.setString(4, phoneHash);
            stmt.setString(5, jid);
            stmt.setString(6, displayName);

            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new SQLException("INSERT failed, no rows affected");
            }

            long id;
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No ID obtained");
                }
                id = keys.getLong(1);
            }

            conn.commit();

            logger.info("New user registered: userId=" + userId
                    + " jid=" + jid);

            return new UserRecord(
                    id,
                    userId,
                    jid,
                    displayName,
                    "",
                    true
            );
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

    public Optional<String> getIdentityKey(String uid) {
        String sql = "SELECT identity_key FROM signal_identity_bundles WHERE user_id = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, uid);
            ResultSet rs = stmt.executeQuery();
            if(rs.next()) return Optional.of(rs.getString("identity_key"));
        } catch (SQLException e) {
            logger.info("getIdentityKey error: "+e.getMessage());
        }
        return Optional.empty();
    }

    public void updateGroupMemberLastSyncVersion(String userId, String groupId, int version) {
        String sql = "UPDATE group_members SET last_synced_version = ? WHERE group_id = ? AND user_id = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setLong(1, version);
            stmt.setString(2, groupId);
            stmt.setString(3, userId);
            int result = stmt.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            logger.info("updateGroupMemberLastSyncVersion error: "+e.getMessage());
        }
    }

    public Optional<String> getUserLastSeen(String userId) {
        String sql = "SELECT last_seen FROM users WHERE user_id = ? LIMIT 1";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {

                if (rs.next()) {

                    Timestamp timestamp = rs.getTimestamp("last_seen");

                    if (timestamp != null) {
                        return Optional.of(timestamp.toInstant().toString());
                    }
                }
            }

        } catch (SQLException e) {
            logger.warning("getUserLastSeen error: " + e.getMessage());
        }

        return Optional.empty();
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
            WHERE user_id = ?
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

    //=====================================================
    //              GROUP UTILS
    //=====================================================

    public Optional<GroupController.ChatGroup> getGroup(String groupId){
        return Optional.empty();
    }

    /**
     * Dummy helper illustrating where you'd optionally pare down raw JSON structures
     * on-the-fly before sending them out over XMPP.
     */
    private String extractChangedFieldsOnly(String rawJson) {
        // TODO: Use Jackson/Gson to parse and return minimal mutations if necessary.
        // If it is already store field-level increments natively on write, just return it.
        return rawJson;
    }

    public SyncBacklogResponse getOptimizedSyncBacklog(String userId) {
        // Temp mapping: GroupID -> List of processed deltas
        Map<String, List<GroupEventDelta>> deltaMap = new HashMap<>();
        Map<String, Integer> serverVersions = new HashMap<>();

        // Keep it fast: Join the index markers, look up target events chronologically
        String sql = """
                    SELECT
                        m.group_id,
                        g.state_version,
                        ge.event_id,
                        ge.event_type,
                        ge.payload,
                        ge.actor_user_id, -- The initiator of the event
                        ge.target_user_id, -- The receiver of the event
                        UNIX_TIMESTAMP(ge.created_at) as event_time
                    FROM group_members m
                    INNER JOIN `groups` g ON m.group_id = g.group_id
                    INNER JOIN group_state_events ge ON m.group_id = ge.group_id
                    WHERE m.user_id = ? AND g.state_version > m.last_synced_version
                    -- THE OPTIMIZATION: Use the version sequence directly!
                    AND ge.state_version > m.last_synced_version
                    ORDER BY ge.state_version ASC;
                    """;

        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet r = stmt.executeQuery()) {
                while (r.next()) {
                    String groupId = r.getString("group_id");
                    int currentServerVersion = r.getInt("state_version");
                    String eventType = r.getString("event_type");
                    String rawPayload = r.getString("payload");
                    String actorUid = r.getString("actor_user_id"); // can be null
                    String subjectUid = r.getString("target_user_id"); // can be null

                    serverVersions.put(groupId, currentServerVersion);

                    String processedPayload = rawPayload;

                    if ("metadata_changed".equals(eventType)) {
                        // If the payload contains the full historical settings dump,
                        // compress it here to extract only the specific keys that mutated
                        processedPayload = extractChangedFieldsOnly(rawPayload);
                    }

                    GroupEventDelta delta = new GroupEventDelta(
                            r.getString("event_id"),
                            actorUid,
                            subjectUid,
                            eventType,
                            processedPayload,
                            r.getLong("event_time")
                    );

                    deltaMap.computeIfAbsent(groupId, k -> new ArrayList<>()).add(delta);
                }
            }
        } catch (SQLException e) {
            logger.severe("getOptimizedSyncBacklog Database Error: " + e.getMessage());
        }

        // Convert our working map cleanly into our final structural DTO payload array
        List<GroupSyncPayload> groupSyncList = new ArrayList<>();
        for (Map.Entry<String, List<GroupEventDelta>> entry : deltaMap.entrySet()) {
            String gId = entry.getKey();
            groupSyncList.add(new GroupSyncPayload(
                    gId,
                    serverVersions.get(gId),
                    entry.getValue()
            ));
        }

        return new SyncBacklogResponse(userId, groupSyncList);
    }

    // 1. Represents an optimized, field-level event timeline record
    public record GroupEventDelta(
            String eventId,
            String actorUid,
            String targetUid,
            String eventType,
            String payload, // Contains ONLY changed fields for configs, or user info for membership
            long createdAt
    ) {}

    // 2. Holds the final consolidated payload for a single group
    public record GroupSyncPayload(
            String groupId,
            int latestServerVersion,
            List<GroupEventDelta> deltas
    ) {}

    // 3. Top-level wrapper containing the entire synchronization state for the user session
    public record SyncBacklogResponse(
            String userId,
            List<GroupSyncPayload> groupSyncs
    ) {}

    public boolean isGroupAdmin(String groupId, String userId){
        return false;
    }

    public boolean persistGroupPresence(String fromId, String toId, String affilitation,
                                        String jid, String role, boolean selfStatus){
        String sql= """
                INSERT INTO group_presence VALUES(?,?,?,?,?,?)
                """;
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, fromId);
            stmt.setString(2, toId);
            stmt.setString(3, affilitation);
            stmt.setString(4, jid);
            stmt.setString(5, role);
            stmt.setBoolean(6, selfStatus);

            int res = stmt.executeUpdate();

            return true;
        }catch (SQLException exception){
            logger.info("persistGroupPresence error: "+exception.getMessage());
            return false;
        }
    }


    public boolean isTokenExist(String token){
        String sql = """
                SELECT * FROM invite_links WHERE token = ? LIMIT 1
                """;
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, token);

            ResultSet resultSet = stmt.executeQuery();
            return resultSet.next();
        }catch (SQLException exception){
            logger.info("Error isTokenExists " + exception.getMessage());
        }
        return false;
    }

    public Optional<GroupController.ChatGroup> getGroupByToken(String token){
        String sql = """
                SELECT group_id FROM invite_links WHERE token = ? AND active = true
                """;
        String groupSql = "SELECT * FROM chat_groups WHERE group_id = ?";

        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, token);

            ResultSet resultSet = stmt.executeQuery();

            PreparedStatement groupStmt = connection.prepareStatement(groupSql);
            groupStmt.setString(1, resultSet.getString("group_id"));

        }catch (SQLException exception){
            logger.info("Error getGroupByToken " + exception.getMessage());
        }

        return Optional.empty();
    }

    public boolean isMember(String groupId, String userId){
        String sql = "SELECT * FROM group_members WHERE group_id = ? AND user_id = ?";

        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, groupId);
            stmt.setString(2, userId);

            ResultSet resultSet = stmt.executeQuery();

            return resultSet.next();

        }catch(SQLException exception){
            logger.info("Error isMember " + exception.getMessage());
        }
        return false;
    }


    public void addMemberIfAbsent(String groupId, String userId) {
        String sql = """
                INSERT INTO group_members (group_id, user_id) VALUES(?,?)
                """;

        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, groupId);
            stmt.setString(2, userId);

            int result = stmt.executeUpdate();

        }catch (SQLException exception){
            logger.info("Error addMemberIfAbsent " + exception.getMessage());
        }
    }

    public void disableInviteToken(String groupID){
        String sql = """
                UPDATE TABLE invite_links SET active = false WHERE groupId = ?
                """;

        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, groupID);
            int result = stmt.executeUpdate();
        }catch (SQLException exception){
            logger.info("Error disabling the invite token " + exception.getMessage());
        }
    }

    public void updateGroupLink(String groupId, String creatorId, String token, boolean active){
        String sql = """
                INSERT INTO invite_links
                (group_id, token, active, created_by, max_uses)
                VALUES (?,?,?,?,?)
                """;
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, groupId);
            stmt.setString(2, token);
            stmt.setBoolean(3, active);
            stmt.setString(4, creatorId);

            int result = stmt.executeUpdate();
        }catch(SQLException exception){}
    }
    // =========================================================================
    // Session Tokens
    // =========================================================================

    /**
     * Stores a new session token in the database.
     * Token is stored as SHA-256 hash - raw token never hits the DB.
     * <p>
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
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NOW(), NOW());
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
                s.user_id,
                s.platform,
                s.revoked_at,
                s.device_label,
                u.jid
            FROM sessions s
            INNER JOIN users u ON u.user_id = s.user_id
            WHERE s.token_hash = ?
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
                        rs.getString("user_id"), // The user Id  u_usetyc
                        rs.getString("jid"), // The user contactId without the resource part u_usetyc@localhost
                        "",
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

            logger.info("Revoked " + rows + " session tokens for userId=" + userId);

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
        logger.info("Updating last seen for user :"+userId + "...");
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

    public record Note(
            String noteId,
            String title,
            String conversationId,
            String creatorId,
            long updatedAt
    ){}

    public List<Note> fetchUserNotes(String userId) {

        List<Note> notes = new ArrayList<>();

        String sql = """
            SELECT
                nm.note_id,
                nm.title,
                nm.conversation_jid,
                nm.creator_user_id,
                nm.updated_at
            FROM note_metadata nm
            INNER JOIN note_participants np
                ON np.note_id = nm.note_id
               AND np.user_id = ?
            WHERE nm.deleted_at IS NULL
            ORDER BY nm.updated_at DESC
            """;

        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1,  userId);
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){

                notes.add(
                        new Note(rs.getString("note_id"),
                                rs.getString("title"),
                                rs.getString("conversation_jid"),
                                rs.getString("creator_user_id"),
                                rs.getTimestamp("updated_at").toInstant().toEpochMilli()));
            }

            return notes;
        } catch (SQLException e) {
            logger.info("fetchUserNotes error: "+e.getMessage());
        }

        return notes;
    }

    public List<String> getContacts(String uid){
        List<String> uids = new ArrayList<>();
        String sql = "SELECT contact_uid FROM contacts_relationships WHERE owner_uid = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, uid);

            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                uids.add(rs.getString("contact_uid"));
            }
        } catch (SQLException e) {
            logger.info("getContacts error: "+e.getMessage());
        }
        return uids;
    }

    // =========================================================================
    // Audit Log
    // =========================================================================

    public void insertAuditLog(String eventType,
                                String userId,
                                String details,
                                String ipAddress) {
        /*String sql = """
            INSERT INTO audit_log (event_type, user_id, details, ip_address, created_at)
            VALUES (?, ?, ?::jsonb, ?::inet, NOW())
            """;*/
        String sql = """
    INSERT INTO audit_log (
        event_type,
        user_id,
        details,
        ip_address,
        created_at
    ) VALUES (?, ?, ?, ?, NOW())
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
    // =========================================================================

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
        String selectSql = """
        SELECT from_user_id, message_type, encrypted_content, message_id, created_at, type, group_id
        FROM offline_messages
        WHERE to_user_id = ?
        ORDER BY created_at ASC
    """;

        String deleteSql = """
        DELETE FROM offline_messages
        WHERE to_user_id = ?
    """;

        List<OfflineMessage> messages = new ArrayList<>();

        try (Connection conn = pool.getConnection()) {

            conn.setAutoCommit(false);

            // 1. fetch
            try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                stmt.setString(1, contactJid);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        messages.add(new OfflineMessage(
                                rs.getString("from_user_id"),
                                rs.getString("encrypted_content"),
                                rs.getString("message_type"),
                                rs.getString("message_id"),
                                rs.getString("group_id"),
                                rs.getString("type"),
                                rs.getTimestamp("created_at")
                        ));
                    }
                }
            }

            // 2. delete
            try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setString(1, contactJid);
                stmt.executeUpdate();
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
                    u.jid AS contact_jid,
                    ri.nickname AS name,
                    ri.subscription,
                    ri.ask
                FROM roster_items ri
                JOIN users owner
                    ON owner.user_id = ri.owner_user_id
                LEFT JOIN users u
                    ON u.user_id = ri.contact_user_id
                WHERE owner.jid = ?
                  AND ri.blocked = FALSE
                ORDER BY u.jid;
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

    public Optional<PrivacyHandler.Privacy> getPrivacy(String userId){
        String sql = "SELECT * FROM user_privacy WHERE user_id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PrivacyHandler.Privacy(
                        rs.getString("last_seen_visibility"),
                        rs.getString("photo_visibility"),
                        rs.getString("about_visibility"),
                        rs.getBoolean("read_receipts_enabled")
                ));
            }

        } catch (SQLException e) {
            logger.severe("getRosterItem error: " + e.getMessage());
            return null;
        }
    }

    public boolean insertPrivacy(String userId, PrivacyHandler.Privacy privacy){
        String sql = """
            INSERT INTO user_privacy
                (user_id, last_seen_visibility, photo_visibility,
                 about_visibility, read_receipts_enabled) VALUES(?, ?, ?, ?, ?)
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, privacy.lastSeenVisibility());
            stmt.setString(3, privacy.photoVisibility());
            stmt.setString(4, privacy.aboutVisibility());
            stmt.setBoolean(5, privacy.readReceiptsEnabled());
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("insertPrivacy error: " + e.getMessage());
            return false;
        }
    }

    public boolean upsertPrivacy(String userId, PrivacyHandler.Privacy privacy) {

        logger.info("Updating the user privacy");

        String sql = """
        INSERT INTO user_privacy (
            user_id,
            last_seen_visibility,
            photo_visibility,
            about_visibility,
            read_receipts_enabled
        )
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            last_seen_visibility  = VALUES(last_seen_visibility),
            photo_visibility      = VALUES(photo_visibility),
            about_visibility      = VALUES(about_visibility),
            read_receipts_enabled = VALUES(read_receipts_enabled),
            updated_at            = NOW()
        """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, privacy.lastSeenVisibility());
            stmt.setString(3, privacy.photoVisibility());
            stmt.setString(4, privacy.aboutVisibility());
            stmt.setBoolean(5, privacy.readReceiptsEnabled());

            stmt.executeUpdate();
            conn.commit();

            return true;

        } catch (SQLException e) {
            logger.severe("upsertPrivacy error: " + e.getMessage());
            return false;
        }
    }


    // NEW - exceptions
    public List<PrivacyHandler.PrivacyException> getPrivacyExceptions(String jid){
        String sql = "SELECT * FROM privacy_exceptions WHERE owner_user _id = ?";
        List<PrivacyHandler.PrivacyException> exceptions = new ArrayList<>();
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, jid);
            ResultSet r = stmt.executeQuery();
            connection.commit();
            while(r.next()){
                exceptions.add(new PrivacyHandler.PrivacyException(
                        r.getString("field"),
                        PrivacyHandler.PrivacyException.ExceptionType.valueOf(r.getString("type".toUpperCase())),
                        r.getString("target_user_id")));
            }

            return exceptions;
        }catch (SQLException exception){
            logger.info("Error getPrivacyExceptions");
            return exceptions;
        }
    }

    // NEW - blocking
    public boolean blockUser(String owner, String target){
        String sql = "INSERT INTO blocked_users(owner_user_id, blocked_user_id) VALUES(?,?)";
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, owner);
            stmt.setString(2, target);
            stmt.executeUpdate();
            conn.commit();
            logger.info("Successfully blocked a user");
            return true;

        } catch (SQLException e) {
            logger.severe("deletePendingSubscription error: " + e.getMessage());
            return false;
        }
    }

    public boolean unblockUser(String owner, String target){
        //TODO:
        return false;
    }
    public boolean unblockAll(String owner){
        // TODO:
        return false;
    }
    public List<String> getBlockList(String owner){
        return Collections.emptyList();
    }
    public boolean isBlocked(String owner, String target){
        logger.info("Checking if the owner has blocked the user");
        String sql = "SELECT * FROM blocked_users WHERE owner_user_id = ? AND blocked_user_id = ?";
        try(Connection conn = pool.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, owner);
            stmt.setString(2, target);
            ResultSet rs = stmt.executeQuery();
            conn.commit();
            return rs.next();
        }catch (SQLException exception){
            logger.info("Error isBlocked: "+exception.getLocalizedMessage());
        }
        return false;
    }

    // =================================================================
    // Profile Changed Helpers
    // ================================================================
    public boolean changeAvatarUrl(String uid, String avatarUrl){
        String sql = "UPDATE users SET avatar_url = ? WHERE user_id = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);

            stmt.setString(1, avatarUrl);
            stmt.setString(2, uid);

            int rs = stmt.executeUpdate();
            connection.commit();

            return rs > 0;
        } catch (SQLException e) {
            logger.info("changeAvatarUrl error: "+e.getMessage());
        }

        return false;
    }

    public boolean changeDisplayName(String uid, String displayName){
        String sql = "UPDATE users SET display_name = ? WHERE user_id = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);

            stmt.setString(1, displayName);
            stmt.setString(2, uid);

            int rs = stmt.executeUpdate();

            connection.commit();

            return rs > 0;
        } catch (SQLException e) {
            logger.info("changeDisplayName error: "+e.getMessage());
        }
        return false;
    }

    public boolean changeDisplayStatus(String uid, String displayStatus){
        String sql = "UPDATE users SET display_status = ? WHERE user_id = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);

            stmt.setString(1, displayStatus);
            stmt.setString(2, uid);

            int rs = stmt.executeUpdate();
            connection.commit();
            return rs > 0;
        } catch (SQLException e) {
            logger.info("changeDisplayStatus error: "+e.getMessage());
        }
        return false;
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
     * <p>
     * In production: use AES-256-GCM with a key from
     * an HSM or KMS (AWS KMS, Google Cloud KMS).
     * <p>
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
                "",
                rs.getBoolean("active")
        );
    }

    // =========================================================================
    // Value objects
    // =========================================================================

    /**
     * id       → MySQL auto-incremented BIGSERIAL
     *            Generated by Postgres, never by the code
     *            Used only for internal DB joins
     * <p>
     * user_id  → "u_7f3a9b2c" generated by generateUserId()
     *            Used externally in JIDs, API responses, etc.
     */
    public record UserRecord(
            long id,           // BIGSERIAL from Postgres - auto generated
            String userId,     // "u_7f3a9b2c" - generated by generateUserId()
            String jid,        // "u_7f3a9b2c@domain.com"
            String displayName,
            String avatarUrl,
            boolean active
    ) {}

    public record SessionTokenRecord(
            String userId,
            String contactId,
            String pushToken,
            String platform,
            Instant revokedAt,  // null = not revoked
            Instant expiresAt,  // null = never expires
            String deviceLabel
    ) {}

    public record OfflineMessage(
            String fromJid,
            String body,
            String messageType,
            String messageId,
            String groupId,
            String type,
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
     * <p>
     * The server stores CIPHERTEXT only.
     * It never sees the plaintext content.
     *
     * @param messageId        Client-generated UUID for this message
     * @param messageType      text | image | video | audio | file | location
     * @param encryptedContent Base64 AES-256-GCM ciphertext
     * @param iv               Base64 12-byte IV
     * @param mediaStorageKey  Object storage path (null for text messages)
     * @param mimeType         MIME type hint (null for text messages)
     * @param fileSizeBytes    File size in bytes (0 for text messages)
     * @param replyToId        UUID of message being replied to (null if none)
     */
    public boolean storeGroupEncryptedMessage(
            String groupId,
                                        String senderId,
                                         String memberId,
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
                INSERT INTO offline_messages (
                    message_id,
                    group_id,
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
                    expires_at,
                    type
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'pending',true, NOW(), NOW() + INTERVAL 30 DAY, ?)
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.setString(2, groupId);
            stmt.setString(3, senderId);
            stmt.setString(4, memberId);
            stmt.setString(5, messageType);
            stmt.setString(6, encryptedContent);
            stmt.setString(7, iv);
            stmt.setString(8, mediaStorageKey);
            stmt.setString(9, encryptedMetadata);
            stmt.setString(10, mimeType);
            stmt.setLong(11, fileSizeBytes);
            stmt.setString(12, replyToId);
            stmt.setString(13, "groupchat");

            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("storeEncryptedMessage error: " + e.getMessage());
            return false;
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
                INSERT INTO offline_messages (
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
     * @param mimeType         MIME type hint (null for text messages)
     * @param replyToId        UUID of message being replied to (null if none)
     */
    public boolean storeEncryptedMessage(String fromJid,
                                         String toJid,
                                         String messageId,
                                         String messageType,
                                         String encryptedContent,
                                         String mimeType,
                                         String replyToId) {
        String sql = """
                INSERT INTO offline_messages (
                    message_id,
                    from_user_id,
                    to_user_id,
                    message_type,
                    encrypted_content,
                    mime_type,
                    reply_to_message_id,
                    status,
                    was_offline,
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
            stmt.setString(4, mimeType);
            stmt.setString(5, replyToId);
            stmt.setString(6, fromJid);
            stmt.setString(7, toJid);

            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("storeEncryptedMessage error: " + e.getMessage());
            return false;
        }
    }

    public boolean storeSystemEvent(
            String messageId,
            String groupId,
            String eventType,
            String actorId,
            String subjectId,
            String to
    ) {
        String sql = """
        INSERT INTO offline_messages (
            message_id,
            from_user_id,
            to_user_id,
            message_type,
            system_event_type,
            system_actor_id,
            system_subject_id,
            status,
            was_offline,
            created_at,
            expires_at
        )
        VALUES (?, ?, ?, 'system', ?, ?, ?, 'pending', true, NOW(), NOW() + INTERVAL 30 DAY)
    """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.setString(2, actorId);     // or group JID mapping
            stmt.setString(3, groupId);
            stmt.setString(4, eventType);
            stmt.setString(5, actorId);
            stmt.setString(6, subjectId);

            //int rows = stmt.executeUpdate();
            //conn.commit();
            //return rows > 0;
            return false;

        } catch (SQLException e) {
            logger.severe("storeSystemEvent error: " + e.getMessage());
            return false;
        }
    }

    public record OfflineMailEvent(
            String receiverId,
            String senderId,
            String payload
    ){}

    public boolean storeMailEvent(
            String senderId,
            String receiverId,
            String field,
            String value
    ) {
        String sql = """
        INSERT INTO offline_mail_box (
            recipient_id,
            sender_id,
            event_type,
            payload
        ) VALUES (
            ?, ?, 'PROFILE_UPDATE', JSON_OBJECT(?, ?)
        ) ON DUPLICATE KEY UPDATE
            payload = JSON_SET(payload, CONCAT('$.', ?), ?)
    """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, receiverId);
            stmt.setString(2, senderId);
            stmt.setString(3, field);
            stmt.setString(4, value);
            stmt.setString(5, field);
            stmt.setString(6, value);

            int result = stmt.executeUpdate();
            conn.commit();
            return result > 0;
        } catch (SQLException e) {
            logger.severe("storeSystemEvent error: " + e.getMessage());
            return false;
        }
    }

    public List<OfflineMailEvent> getOfflineMailEvents(String uid){
        List<OfflineMailEvent> events = new ArrayList<>();

        logger.info("Fetching mail event for user with id: "+uid);
        String sql = "SELECT * FROM offline_mail_box WHERE recipient_id = ?";
        try(Connection connection = pool.getConnection()){
            PreparedStatement stmt = connection.prepareStatement(sql);
            stmt.setString(1, uid);
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                events.add(new OfflineMailEvent(rs.getString("recipient_id"), rs.getString("sender_id"), rs.getString("payload")));
            }
            connection.commit();
        }catch(SQLException exception){
            logger.info("getOfflineMailEvent error: "+exception.getMessage());
        }
        return events;
    }


    /**
     * Fetches and deletes all pending offline messages for a user.
     * <p>
     * Uses DELETE ... RETURNING for atomic fetch-and-delete.
     * If server crashes after DELETE but before delivery:
     * messages are lost. XEP-0198 Stream Management handles this
     * at the XMPP layer (client will request retransmission).
     * <p>
     * Messages are ordered by created_at ASC so older messages
     * are delivered first (correct chronological order).
     */
    public List<EncryptedOfflineMessage> fetchEncryptedOfflineMessages(
            String toJid) {
        logger.info("Fetching offline messages...");
        String sql = """
            DELETE FROM offline_messages
            WHERE to_user_id = (
                SELECT user_id FROM users WHERE jid = ?
            )
            --comments out AND status = 'pending'
            RETURNING
                message_id,
                (SELECT jid FROM users WHERE user_id = from_user_id) AS from_jid,
                message_type,
                encrypted_content,
                iv,
                media_storage_key,
                encrypted_metadata,
                mime_type,
                file_size_bytes,
                (SELECT message_id FROM messages m2
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
     * Marks a message as delivered also updates the users last_ulid to this message ulid.
     * Called when the recipient's session acknowledges receipt.
     */
    public void markMessageDelivered(String messageId) {
        logger.info("Marking message as delivered");
        String sql = """
            UPDATE offline_messages
            SET status       = 'delivered',
                delivered_at = NOW()
            WHERE message_id = ?
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

    // =========================================================================
    // Offline Receipts Methods
    // =========================================================================

    public void storeReceipt(String from, String to, String id, String type){
        logger.info("Persisting the receipt of type :"+type+" :to use: "+to);
        String finalTo = to;
        if(to.contains("@")) finalTo = to.split("@")[0];
        String sql = """
                INSERT INTO pending_receipts (from_uid, to_uid, message_id, receipt_type) VALUES(
                    ?, ?, ?, ?
                )
                """;

        try(Connection conn = pool.getConnection()){
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, from);
            stmt.setString(2, finalTo);
            stmt.setString(3, id);
            stmt.setString(4, type);

            stmt.executeUpdate();

            conn.commit();
        }catch (SQLException exception){
            logger.info("storeReceipts error "+exception.getMessage());
        }
    }

    public List<OfflineReceipt> getOfflineReceipt(String uid){
        String selectSql = "SELECT * FROM pending_receipts WHERE to_uid = ?";
        String deleteSql = "DELETE FROM pending_receipts WHERE to_uid = ?";

        List<OfflineReceipt> receipts = new ArrayList<>();

        try(Connection conn = pool.getConnection()){
            conn.setAutoCommit(false);

            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setString(1, uid);

                try (ResultSet rs = selectStmt.executeQuery()) {
                    while (rs.next()) {
                        receipts.add(new OfflineReceipt(
                                rs.getString("from_uid"),
                                rs.getString("to_uid"),
                                rs.getString("message_id"),
                                rs.getString("receipt_type")
                        ));
                    }
                }
            }

            try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                deleteStmt.setString(1, uid);
                deleteStmt.executeUpdate();
            }

            conn.commit();

        } catch(SQLException exception){
            logger.info("getOfflineReceipt error " + exception.getMessage());
        }

        return receipts;
    }


    public record OfflineReceipt(
            String fromUid,
            String toUid,
            String messageId,
            String receiptType
    ) {}


    /**
     * Fetches a single, perfectly sorted timeline delta for a user across all
     * 1-to-1 chats and authorized group membership windows.
     * * @param userId The ID of the connecting user (e.g., 'alice_id')
     * @param clientLastSeenUlid The highest ULID string the client has stored locally
     * @param limit The maximum number of timeline events to return in a single page
     */
    public List<GroupRepository.UnifiedTimelineItem> getUnifiedTimelineDelta(String userId, String clientLastSeenUlid, int limit) {
        List<GroupRepository.UnifiedTimelineItem> timeline = new ArrayList<>();

        String sql = """
                SELECT
                    e.event_id,
                    e.event_category,
                    e.event_type,
                    e.group_id,
                    e.sender_id,
                    e.sender_jid,
                    e.group_version,
                    e.epoch,
                    e.encrypted_content,
                    e.reply_to_event_id,
                    e.created_at
                FROM events e
                LEFT JOIN group_members m
                    ON e.group_id = m.group_id
                   AND m.user_id = ?
                WHERE
                    (
                        e.event_category = 'chat'
                        AND e.recipient_id = ?
                        AND e.event_id > ?
                    )
                    OR
                    (
                        e.event_category IN ('groupchat', 'system')
                        AND m.user_id IS NOT NULL
                        AND e.event_id > ?
                        AND e.sender_id != ?
                        AND e.created_at >= m.joined_at
                        AND (
                            m.left_at IS NULL
                            OR e.created_at <= m.left_at
                        )
                    )
                ORDER BY e.event_id ASC
                LIMIT ?;
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);             // LEFT JOIN
            stmt.setString(2, userId);             // recipient_id
            stmt.setString(3, clientLastSeenUlid); // chat anchor
            stmt.setString(4, clientLastSeenUlid); // group anchor
            stmt.setString(5, userId);             // exclude own messages
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

    private GroupRepository.UnifiedTimelineItem mapRowToTimelineItem(ResultSet rs) throws SQLException {
        return new GroupRepository.UnifiedTimelineItem(
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


    // =========================================================================
    // Push Token Operations
    // =========================================================================

    /**
     * Retrieves the most recently used push token for a user.
     * Used by PushNotificationService to deliver notifications
     * when the user has multiple devices registered.
     * <p>
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
}