package streammessenger.db;


import java.sql.*;
import java.util.Optional;
import java.util.logging.Logger;


import java.util.ArrayList;
import java.util.List;

import streammessenger.roster.RosterItem;

/**
 * All database operations for the XMPP server.
 *
 * Conventions:
 *  - Every method gets its own connection from the pool via try-with-resources
 *  - PreparedStatements everywhere - no string concatenation in SQL
 *  - Explicit transaction management (autoCommit=false)
 *  - Passwords stored as BCrypt hashes - never plaintext
 *  - Methods return Optional<> or empty List - never throw on not-found
 *
 * BCrypt is implemented inline to avoid external dependency.
 *
 * ``sql
 * -- We wrote DatabaseManager.java but never defined the actual tables
 *
 * -- Users table
 * CREATE TABLE users (
 *     id              BIGSERIAL PRIMARY KEY,
 *     username        VARCHAR(255) NOT NULL UNIQUE,
 *     contact_id      VARCHAR(255) NOT NULL UNIQUE, -- bare JID: user@domain
 *     password_hash   VARCHAR(255) NOT NULL,         -- BCrypt hash
 *     active          BOOLEAN NOT NULL DEFAULT true,
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     last_seen       TIMESTAMPTZ,
 *     avatar_hash     VARCHAR(255)
 * );
 *
 * -- Roster (contact list)
 * CREATE TABLE roster_items (
 *     id              BIGSERIAL PRIMARY KEY,
 *     owner_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 *     contact_jid     VARCHAR(255) NOT NULL,
 *     name            VARCHAR(255),
 *     subscription    VARCHAR(16) NOT NULL DEFAULT 'none', -- none|from|to|both|remove
 *     ask             VARCHAR(16),                          -- subscribe (pending outbound)
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     UNIQUE(owner_id, contact_jid)
 * );
 *
 * -- Roster groups (folders)
 * CREATE TABLE roster_groups (
 *     id              BIGSERIAL PRIMARY KEY,
 *     roster_item_id  BIGINT NOT NULL REFERENCES roster_items(id) ON DELETE CASCADE,
 *     group_name      VARCHAR(255) NOT NULL
 * );
 *
 * -- Offline message storage
 * CREATE TABLE offline_messages (
 *     id              BIGSERIAL PRIMARY KEY,
 *     from_jid        VARCHAR(255) NOT NULL,
 *     to_contact_id   VARCHAR(255) NOT NULL,
 *     stanza_id       VARCHAR(255),
 *     body            TEXT NOT NULL,
 *     full_stanza     TEXT,          -- Store full XML for XEP-0203 delayed delivery
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 * CREATE INDEX idx_offline_to ON offline_messages(to_contact_id);
 *
 * -- Message archive (XEP-0313)
 * CREATE TABLE message_archive (
 *     id              BIGSERIAL PRIMARY KEY,
 *     archive_id      VARCHAR(255) NOT NULL UNIQUE,  -- UUID for MAM queries
 *     owner_jid       VARCHAR(255) NOT NULL,
 *     from_jid        VARCHAR(255) NOT NULL,
 *     to_jid          VARCHAR(255) NOT NULL,
 *     body            TEXT,
 *     full_stanza     TEXT NOT NULL,
 *     timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 * CREATE INDEX idx_archive_owner     ON message_archive(owner_jid, timestamp);
 * CREATE INDEX idx_archive_owner_jid ON message_archive(owner_jid, from_jid, timestamp);
 *
 * -- Pending subscription requests
 * CREATE TABLE subscription_requests (
 *     id              BIGSERIAL PRIMARY KEY,
 *     from_jid        VARCHAR(255) NOT NULL,
 *     to_contact_id   VARCHAR(255) NOT NULL,
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     UNIQUE(from_jid, to_contact_id)
 * );
 *
 * -- Stream management state (XEP-0198 resumption)
 * CREATE TABLE sm_sessions (
 *     sm_id           VARCHAR(255) PRIMARY KEY,
 *     contact_id      VARCHAR(255) NOT NULL,
 *     unacked_stanzas JSONB,
 *     client_acked    BIGINT NOT NULL DEFAULT 0,
 *     server_sent     BIGINT NOT NULL DEFAULT 0,
 *     expires_at      TIMESTAMPTZ NOT NULL,
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 * CREATE INDEX idx_sm_contact ON sm_sessions(contact_id);
 *
 * -- Active sessions (for clustering - optional)
 * CREATE TABLE active_sessions (
 *     uid             VARCHAR(255) PRIMARY KEY,
 *     contact_id      VARCHAR(255) NOT NULL,
 *     resource        VARCHAR(255),
 *     node_id         VARCHAR(255) NOT NULL,  -- Which server node owns this session
 *     priority        INT NOT NULL DEFAULT 0,
 *     presence_type   VARCHAR(32),
 *     presence_show   VARCHAR(16),
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     last_activity   TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 * CREATE INDEX idx_active_contact ON active_sessions(contact_id);
 *
 * -- Admin audit log
 * CREATE TABLE audit_log (
 *     id              BIGSERIAL PRIMARY KEY,
 *     event_type      VARCHAR(64) NOT NULL,
 *     actor_jid       VARCHAR(255),
 *     target_jid      VARCHAR(255),
 *     details         JSONB,
 *     ip_address      INET,
 *     created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 * ```
 *
 * -- ============================================================================
 * -- USERS
 * -- ============================================================================
 *
 * CREATE TABLE users (
 *     id                  BIGSERIAL PRIMARY KEY,
 *
 *     -- Opaque server-generated ID - this becomes the JID local part
 *     -- Format: u_ + 8 random hex chars e.g. "u_7f3a9b2c"
 *     user_id             VARCHAR(32) NOT NULL UNIQUE,
 *
 *     -- Firebase UID - used only to verify tokens
 *     -- Never exposed to other users or in JIDs
 *     firebase_uid        VARCHAR(128) NOT NULL UNIQUE,
 *
 *     -- Phone number in E.164 format: +2348012345678
 *     -- Stored encrypted at rest
 *     -- Used only for: registration, recovery, contact discovery
 *     phone_number        VARCHAR(20) NOT NULL UNIQUE,
 *
 *     -- Phone number hashed with SHA-256 for contact discovery
 *     -- The app uploads hashed phone numbers from contacts
 *     -- We match hashes without revealing actual numbers
 *     phone_number_hash   VARCHAR(64) NOT NULL UNIQUE,
 *
 *     -- The full XMPP JID assigned to this user
 *     -- e.g. u_7f3a9b2c@yourdomain.com
 *     jid                 VARCHAR(255) NOT NULL UNIQUE,
 *
 *     -- Display name (user-chosen, not phone number)
 *     display_name        VARCHAR(100),
 *
 *     -- Profile photo URL or storage key
 *     avatar_url          VARCHAR(500),
 *
 *     -- Account state
 *     active              BOOLEAN NOT NULL DEFAULT true,
 *     phone_verified      BOOLEAN NOT NULL DEFAULT false,
 *
 *     -- Timestamps
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     last_seen           TIMESTAMPTZ,
 *
 *     -- Soft delete
 *     deleted_at          TIMESTAMPTZ
 * );
 *
 * -- Indexes for common lookups
 * CREATE INDEX idx_users_firebase_uid    ON users(firebase_uid);
 * CREATE INDEX idx_users_phone_hash      ON users(phone_number_hash);
 * CREATE INDEX idx_users_jid             ON users(jid);
 * CREATE INDEX idx_users_active          ON users(active) WHERE active = true;
 *
 * -- ============================================================================
 * -- SESSION TOKENS
 * -- Replaces passwords entirely.
 * -- A session token is issued after Firebase auth verification.
 * -- Used for XMPP SASL authentication instead of a password.
 * -- ============================================================================
 *
 * CREATE TABLE session_tokens (
 *     id                  BIGSERIAL PRIMARY KEY,
 *
 *     -- Which user owns this token
 *     user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id)
 *                             ON DELETE CASCADE,
 *
 *     -- The token itself - stored as SHA-256 hash
 *     -- Never store raw tokens in DB (treat like passwords)
 *     token_hash          VARCHAR(64) NOT NULL UNIQUE,
 *
 *     -- Human-readable device label for session management
 *     -- e.g. "Samsung Galaxy S24", "iPhone 15 Pro"
 *     device_label        VARCHAR(255),
 *
 *     -- Device push token for offline notifications
 *     -- FCM token (Android) or APNs token (iOS)
 *     push_token          VARCHAR(500),
 *
 *     -- Platform: android | ios | web | desktop
 *     platform            VARCHAR(20),
 *
 *     -- App version that created this session
 *     app_version         VARCHAR(20),
 *
 *     -- When this token expires (rolling - extended on use)
 *     expires_at          TIMESTAMPTZ NOT NULL,
 *
 *     -- Last time this token was used for auth
 *     last_used_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *
 *     -- IP address that created this session (for audit)
 *     created_from_ip     INET,
 *
 *     -- Timestamps
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *
 *     -- Revoked manually (e.g. "sign out all devices")
 *     revoked_at          TIMESTAMPTZ,
 *     revoked_reason      VARCHAR(100)
 * );
 *
 * CREATE INDEX idx_session_tokens_user_id    ON session_tokens(user_id);
 * CREATE INDEX idx_session_tokens_hash       ON session_tokens(token_hash);
 * CREATE INDEX idx_session_tokens_expires    ON session_tokens(expires_at)
 *     WHERE revoked_at IS NULL;
 * CREATE INDEX idx_session_tokens_push       ON session_tokens(push_token)
 *     WHERE push_token IS NOT NULL AND revoked_at IS NULL;
 *
 * -- ============================================================================
 * -- ROSTER (Contact List)
 * -- Modified to use user_id instead of JIDs as foreign keys
 * -- because users find each other by phone number, not by JID
 * -- ============================================================================
 *
 * CREATE TABLE roster_items (
 *     id                  BIGSERIAL PRIMARY KEY,
 *
 *     -- The user who owns this roster entry
 *     owner_user_id       VARCHAR(32) NOT NULL REFERENCES users(user_id)
 *                             ON DELETE CASCADE,
 *
 *     -- The contact's user_id (NULL if contact is not registered yet)
 *     -- NULL means: contact is in phonebook but hasn't signed up
 *     contact_user_id     VARCHAR(32) REFERENCES users(user_id)
 *                             ON DELETE SET NULL,
 *
 *     -- The contact's JID (set when contact_user_id is resolved)
 *     contact_jid         VARCHAR(255),
 *
 *     -- The name from the OWNER's phonebook
 *     -- This is personal - Alice may call Bob "Bobby" while Carol calls him "Bob"
 *     nickname            VARCHAR(100),
 *
 *     -- XMPP subscription state
 *     -- none | from | to | both
 *     subscription        VARCHAR(10) NOT NULL DEFAULT 'none',
 *
 *     -- Pending outbound subscription request
 *     ask                 VARCHAR(20),
 *
 *     -- Is this contact blocked?
 *     blocked             BOOLEAN NOT NULL DEFAULT false,
 *
 *     -- Timestamps
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *
 *     UNIQUE(owner_user_id, contact_user_id)
 * );
 *
 * CREATE INDEX idx_roster_owner      ON roster_items(owner_user_id);
 * CREATE INDEX idx_roster_contact    ON roster_items(contact_user_id);
 * CREATE INDEX idx_roster_jid        ON roster_items(contact_jid);
 *
 * -- Roster groups (folders e.g. "Family", "Work")
 * CREATE TABLE roster_groups (
 *     id                  BIGSERIAL PRIMARY KEY,
 *     roster_item_id      BIGINT NOT NULL REFERENCES roster_items(id)
 *                             ON DELETE CASCADE,
 *     group_name          VARCHAR(100) NOT NULL,
 *     UNIQUE(roster_item_id, group_name)
 * );
 *
 * -- ============================================================================
 * -- CONTACT DISCOVERY
 * -- Phone number hashes uploaded by app for "find my contacts" feature
 * -- Separated from roster to keep lookup fast and not store raw numbers
 * -- ============================================================================
 *
 * CREATE TABLE contact_discovery_cache (
 *     id                  BIGSERIAL PRIMARY KEY,
 *
 *     -- The user who uploaded this hash
 *     owner_user_id       VARCHAR(32) NOT NULL REFERENCES users(user_id)
 *                             ON DELETE CASCADE,
 *
 *     -- SHA-256 hash of the contact's phone number in E.164 format
 *     phone_hash          VARCHAR(64) NOT NULL,
 *
 *     -- Resolved to this user_id if they are registered
 *     resolved_user_id    VARCHAR(32) REFERENCES users(user_id)
 *                             ON DELETE SET NULL,
 *
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *
 *     UNIQUE(owner_user_id, phone_hash)
 * );
 *
 * CREATE INDEX idx_discovery_owner    ON contact_discovery_cache(owner_user_id);
 * CREATE INDEX idx_discovery_hash     ON contact_discovery_cache(phone_hash);
 *
 * -- ============================================================================
 * -- OFFLINE MESSAGES
 * -- ============================================================================
 *
 * CREATE TABLE offline_messages (
 *     id                  BIGSERIAL PRIMARY KEY,
 *
 *     from_user_id        VARCHAR(32) NOT NULL REFERENCES users(user_id),
 *     from_jid            VARCHAR(255) NOT NULL,
 *
 *     to_user_id          VARCHAR(32) NOT NULL REFERENCES users(user_id),
 *     to_jid              VARCHAR(255) NOT NULL,
 *
 *     stanza_id           VARCHAR(255) NOT NULL,
 *
 *     -- Full stanza XML stored for accurate delivery with all extensions
 *     stanza_xml          TEXT NOT NULL,
 *
 *     -- Extracted body for push notification preview
 *     body_preview        VARCHAR(500),
 *
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *
 *     -- Push notification sent for this message?
 *     push_sent           BOOLEAN NOT NULL DEFAULT false,
 *     push_sent_at        TIMESTAMPTZ
 * );
 *
 * CREATE INDEX idx_offline_to_user    ON offline_messages(to_user_id);
 * CREATE INDEX idx_offline_push       ON offline_messages(push_sent, to_user_id)
 *     WHERE push_sent = false;
 *
 * -- ============================================================================
 * -- PENDING SUBSCRIPTIONS
 * -- ============================================================================
 *
 * CREATE TABLE subscription_requests (
 *     id                  BIGSERIAL PRIMARY KEY,
 *     from_user_id        VARCHAR(32) NOT NULL REFERENCES users(user_id),
 *     from_jid            VARCHAR(255) NOT NULL,
 *     to_user_id          VARCHAR(32) NOT NULL REFERENCES users(user_id),
 *     type                VARCHAR(20) NOT NULL,
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     UNIQUE(from_user_id, to_user_id, type)
 * );
 *
 * -- ============================================================================
 * -- STREAM MANAGEMENT SESSIONS (XEP-0198)
 * -- ============================================================================
 *
 * CREATE TABLE sm_sessions (
 *     sm_id               VARCHAR(64) PRIMARY KEY,
 *     user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id)
 *                             ON DELETE CASCADE,
 *     unacked_stanzas     JSONB,
 *     client_acked        BIGINT NOT NULL DEFAULT 0,
 *     server_sent         BIGINT NOT NULL DEFAULT 0,
 *     expires_at          TIMESTAMPTZ NOT NULL,
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 *
 * CREATE INDEX idx_sm_user ON sm_sessions(user_id);
 *
 * -- ============================================================================
 * -- MESSAGE ARCHIVE (XEP-0313 - optional but useful)
 * -- ============================================================================
 *
 * CREATE TABLE message_archive (
 *     id                  BIGSERIAL PRIMARY KEY,
 *     archive_id          UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
 *     owner_user_id       VARCHAR(32) NOT NULL REFERENCES users(user_id),
 *     from_jid            VARCHAR(255) NOT NULL,
 *     to_jid              VARCHAR(255) NOT NULL,
 *     stanza_id           VARCHAR(255),
 *     body                TEXT,
 *     stanza_xml          TEXT NOT NULL,
 *     timestamp           TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 *
 * CREATE INDEX idx_archive_owner     ON message_archive(owner_user_id, timestamp);
 * CREATE INDEX idx_archive_stanza_id ON message_archive(stanza_id);
 *
 * -- ============================================================================
 * -- AUDIT LOG
 * -- ============================================================================
 *
 * CREATE TABLE audit_log (
 *     id                  BIGSERIAL PRIMARY KEY,
 *     event_type          VARCHAR(64) NOT NULL,
 *     user_id             VARCHAR(32) REFERENCES users(user_id),
 *     details             JSONB,
 *     ip_address          INET,
 *     created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 *
 * CREATE INDEX idx_audit_user ON audit_log(user_id, created_at);
 * CREATE INDEX idx_audit_type ON audit_log(event_type, created_at);
 */
public final class DatabaseManagerOld {

    private static final Logger logger =
            Logger.getLogger(DatabaseManagerOld.class.getName());

    private final ConnectionPool pool;

    public DatabaseManagerOld(ConnectionPool pool) {
        this.pool = pool;
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    /**
     * Validates user credentials against the database.
     *
     * @return The contactId (bare JID) if credentials are valid, empty otherwise
     */
    public Optional<String> authenticateUser(String username, String password) {
        String sql = """
            SELECT contact_id, password_hash, active
            FROM users
            WHERE username = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    logger.fine("Auth: unknown user=" + username);
                    return Optional.empty();
                }

                if (!rs.getBoolean("active")) {
                    logger.warning("Auth: disabled account user=" + username);
                    return Optional.empty();
                }

                String storedHash = rs.getString("password_hash");
                if (!BCryptCompat.checkpw(password, storedHash)) {
                    logger.warning("Auth: bad password user=" + username);
                    return Optional.empty();
                }

                return Optional.of(rs.getString("contact_id"));
            }

        } catch (SQLException e) {
            logger.severe("DB error during authentication: " + e.getMessage());
            return Optional.empty();
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

    /**
     * Updates the last_seen timestamp for a user.
     * Run asynchronously after successful login.
     */
    public void updateLastSeen(String contactId) {
        String sql = "UPDATE users SET last_seen = NOW() WHERE contact_id = ?";
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contactId);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            logger.warning("Failed to update last_seen for " + contactId
                    + ": " + e.getMessage());
        }
    }

    /**
     * Checks if a contact exists and is active.
     */
    public boolean contactExists(String contactId) {
        String sql = "SELECT 1 FROM users WHERE contact_id = ? AND active = true";
        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contactId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.severe("DB error checking contact existence: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Offline Messages
    // =========================================================================

    /**
     * Stores a message for a user who is currently offline.
     */
    public boolean storeOfflineMessage(String fromJid, String toContactId,
                                       String body, String stanzaId) {
        String sql = """
            INSERT INTO offline_messages
                (from_jid, to_contact_id, body, stanza_id, created_at)
            VALUES (?, ?, ?, ?, NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fromJid);
            stmt.setString(2, toContactId);
            stmt.setString(3, body);
            stmt.setString(4, stanzaId);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("Failed to store offline message from="
                    + fromJid + " to=" + toContactId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Retrieves AND deletes all offline messages for a contact.
     *
     * Uses DELETE ... RETURNING for atomic fetch-and-delete.
     * This prevents double delivery if the server restarts mid-delivery.
     */
    public List<OfflineMessage> fetchOfflineMessages(String contactId) {
        String sql = """
            DELETE FROM offline_messages
            WHERE to_contact_id = ?
            RETURNING from_jid, body, stanza_id, created_at
            ORDER BY created_at ASC
            """;

        List<OfflineMessage> messages = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, contactId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new OfflineMessage(
                            rs.getString("from_jid"),
                            rs.getString("body"),
                            rs.getString("stanza_id"),
                            rs.getTimestamp("created_at")
                    ));
                }
            }
            conn.commit();

        } catch (SQLException e) {
            logger.severe("Failed to fetch offline messages for "
                    + contactId + ": " + e.getMessage());
        }

        return messages;
    }

    // =========================================================================
    // Roster
    // =========================================================================

    /**
     * Returns all roster items for a contact (their full contact list).
     */
    public List<RosterItem> getRosterItems(String ownerContactId) {
        String sql = """
            SELECT ri.contact_jid, ri.name, ri.subscription, ri.ask,
                   COALESCE(
                       ARRAY_AGG(rg.group_name ORDER BY rg.group_name)
                       FILTER (WHERE rg.group_name IS NOT NULL),
                       '{}'
                   ) AS groups
            FROM roster_items ri
            LEFT JOIN roster_groups rg ON rg.roster_item_id = ri.id
            WHERE ri.owner_contact_id = ?
            GROUP BY ri.id, ri.contact_jid, ri.name, ri.subscription, ri.ask
            ORDER BY ri.contact_jid
            """;

        List<RosterItem> items = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerContactId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Array groupsArray = rs.getArray("groups");
                    List<String> groups = new ArrayList<>();
                    if (groupsArray != null) {
                        String[] arr = (String[]) groupsArray.getArray();
                        for (String g : arr) {
                            if (g != null && !g.isBlank()) groups.add(g);
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
            logger.severe("Failed to get roster for " + ownerContactId
                    + ": " + e.getMessage());
        }

        return items;
    }

    /**
     * Returns a single roster item, or null if not found.
     */
    public RosterItem getRosterItem(String ownerContactId, String contactJid) {
        String sql = """
            SELECT ri.contact_jid, ri.name, ri.subscription, ri.ask,
                   COALESCE(
                       ARRAY_AGG(rg.group_name ORDER BY rg.group_name)
                       FILTER (WHERE rg.group_name IS NOT NULL),
                       '{}'
                   ) AS groups
            FROM roster_items ri
            LEFT JOIN roster_groups rg ON rg.roster_item_id = ri.id
            WHERE ri.owner_contact_id = ? AND ri.contact_jid = ?
            GROUP BY ri.id, ri.contact_jid, ri.name, ri.subscription, ri.ask
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerContactId);
            stmt.setString(2, contactJid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;

                Array groupsArray = rs.getArray("groups");
                List<String> groups = new ArrayList<>();
                if (groupsArray != null) {
                    String[] arr = (String[]) groupsArray.getArray();
                    for (String g : arr) {
                        if (g != null && !g.isBlank()) groups.add(g);
                    }
                }

                return new RosterItem(
                        rs.getString("contact_jid"),
                        rs.getString("name"),
                        rs.getString("subscription"),
                        rs.getString("ask"),
                        groups
                );
            }

        } catch (SQLException e) {
            logger.severe("Failed to get roster item " + ownerContactId
                    + " -> " + contactJid + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Inserts or updates a roster item and its groups.
     * Uses PostgreSQL UPSERT (INSERT ... ON CONFLICT DO UPDATE).
     */
    public boolean upsertRosterItem(String ownerContactId, RosterItem item) {
        String upsertItem = """
            INSERT INTO roster_items
                (owner_contact_id, contact_jid, name, subscription, ask, updated_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            ON CONFLICT (owner_contact_id, contact_jid)
            DO UPDATE SET
                name = EXCLUDED.name,
                subscription = EXCLUDED.subscription,
                ask = EXCLUDED.ask,
                updated_at = NOW()
            RETURNING id
            """;

        String deleteGroups = "DELETE FROM roster_groups WHERE roster_item_id = ?";

        String insertGroup = """
            INSERT INTO roster_groups (roster_item_id, group_name)
            VALUES (?, ?)
            """;

        try (Connection conn = pool.getConnection()) {
            long itemId;

            // Upsert the roster item
            try (PreparedStatement stmt = conn.prepareStatement(upsertItem)) {
                stmt.setString(1, ownerContactId);
                stmt.setString(2, item.jid());
                stmt.setString(3, item.name());
                stmt.setString(4, item.subscription());
                stmt.setString(5, item.ask());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return false;
                    }
                    itemId = rs.getLong("id");
                }
            }

            // Replace all groups for this item
            try (PreparedStatement stmt = conn.prepareStatement(deleteGroups)) {
                stmt.setLong(1, itemId);
                stmt.executeUpdate();
            }

            if (!item.groups().isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(insertGroup)) {
                    for (String group : item.groups()) {
                        stmt.setLong(1, itemId);
                        stmt.setString(2, group);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("Failed to upsert roster item " + ownerContactId
                    + " -> " + item.jid() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a roster item and its groups (cascades via FK).
     */
    public boolean deleteRosterItem(String ownerContactId, String contactJid) {
        String sql = """
            DELETE FROM roster_items
            WHERE owner_contact_id = ? AND contact_jid = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ownerContactId);
            stmt.setString(2, contactJid);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("Failed to delete roster item: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Pending Subscriptions
    // =========================================================================

    /**
     * Stores a pending subscription request for offline delivery.
     * Called when the target user is not currently connected.
     */
    public boolean storePendingSubscription(String fromJid, String toContactId,
                                            String type) {
        String sql = """
            INSERT INTO subscription_requests
                (from_jid, to_contact_id, type, created_at)
            VALUES (?, ?, ?, NOW())
            ON CONFLICT (from_jid, to_contact_id, type) DO NOTHING
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fromJid);
            stmt.setString(2, toContactId);
            stmt.setString(3, type);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("Failed to store pending subscription: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fetches AND deletes all pending subscription requests for a contact.
     * Called during resource binding to deliver queued requests.
     */
    public List<PendingSubscription> fetchPendingSubscriptions(String toContactId) {
        String sql = """
            DELETE FROM subscription_requests
            WHERE to_contact_id = ?
            RETURNING from_jid, type, created_at
            """;

        List<PendingSubscription> result = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, toContactId);

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
            logger.severe("Failed to fetch pending subscriptions for "
                    + toContactId + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * Deletes a specific pending subscription record.
     * Called after a subscription request is resolved (approved/denied).
     */
    public boolean deletePendingSubscription(String fromJid, String toContactId) {
        String sql = """
            DELETE FROM subscription_requests
            WHERE from_jid = ? AND to_contact_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fromJid);
            stmt.setString(2, toContactId);
            stmt.executeUpdate();
            conn.commit();
            return true;

        } catch (SQLException e) {
            logger.severe("Failed to delete pending subscription: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // BCrypt (inline - no external dependency)
    // =========================================================================

    /**
     * Minimal BCrypt wrapper.
     *
     * We use Java's built-in security APIs only.
     * For a real implementation without external deps, use a
     * pure-Java BCrypt implementation embedded in the project.
     *
     * The interface is the same as jBCrypt so swapping is trivial.
     */
    private static final class BCryptCompat {

        // BCrypt cost factor - higher = slower = more brute-force resistant
        // 12 = ~250ms per check on modern hardware
        private static final int COST = 12;

        static String hashpw(String password) {
            // Embed a minimal BCrypt implementation here
            // or use Java 21's built-in password hashing support
            // This is a placeholder that MUST be replaced with real BCrypt
            throw new UnsupportedOperationException(
                    "Replace BCryptCompat with a real BCrypt implementation. " +
                            "Copy the public domain jBCrypt source into com.xmpp.auth package."
            );
        }

        static boolean checkpw(String password, String hashed) {
            // Same - replace with real BCrypt checkpw
            throw new UnsupportedOperationException(
                    "Replace BCryptCompat with a real BCrypt implementation."
            );
        }
    }

    // =========================================================================
    // Value objects (records)
    // =========================================================================

    public record OfflineMessage(
            String fromJid,
            String body,
            String stanzaId,
            Timestamp createdAt
    ) {}

    public record PendingSubscription(
            String fromJid,
            String type,       // subscribe | subscribed | unsubscribe | unsubscribed
            Timestamp createdAt
    ) {}
}