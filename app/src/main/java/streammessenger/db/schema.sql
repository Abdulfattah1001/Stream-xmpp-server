-- ============================================================================
-- XMPP USERS TABLES
-- ============================================================================

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL UNIQUE,
    firebase_uid VARCHAR(128) NOT NULL UNIQUE,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    phone_number_hash VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    display_status VARCHAR(100),
    avatar_url VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT true,
    phone_verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_seen TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL
);

CREATE INDEX idx_users_firebase_uid ON users(firebase_uid);
CREATE INDEX idx_users_phone_hash ON users(phone_number_hash);
CREATE INDEX idx_users_jid ON users(jid);
CREATE INDEX idx_users_active ON users(active);

-- ============================================================================
-- SESSION TOKENS
-- Replaces passwords entirely.
-- A session token is issued after Firebase auth verification.
-- Used for XMPP SASL authentication instead of a password.
-- ============================================================================

CREATE TABLE session_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    device_label VARCHAR(255),
    push_token VARCHAR(500),
    platform VARCHAR(20),
    app_version VARCHAR(20),
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_from_ip VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    revoked_reason VARCHAR(100),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_tokens_user_id  ON session_tokens(user_id);

CREATE INDEX idx_session_tokens_hash ON session_tokens(token_hash);

CREATE INDEX idx_session_tokens_expires ON session_tokens(expires_at, revoked_at);

CREATE INDEX idx_session_tokens_push ON session_tokens(push_token, revoked_at);


-- ============================================================================
-- ROSTER (Contact List)
-- Modified to use user_id instead of JIDs as foreign keys
-- because users find each other by phone number, not by JID
-- ============================================================================

CREATE TABLE roster_items (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- The user who owns this roster entry
    owner_user_id       VARCHAR(32) NOT NULL,
    -- The contact's user_id (NULL if contact is not registered yet)
    -- NULL means: contact is in phonebook but hasn't signed up
    contact_user_id     VARCHAR(32),
    -- The contact's JID (set when contact_user_id is resolved)
    contact_jid         VARCHAR(255),
    -- The name from the OWNER's phonebook
    nickname            VARCHAR(100),
    -- XMPP subscription state
    -- none | from | to | both
    subscription        VARCHAR(10) NOT NULL DEFAULT 'none',
    -- Pending outbound subscription request
    ask                 VARCHAR(20),
    -- Is this contact blocked?
    blocked             BOOLEAN NOT NULL DEFAULT FALSE,
    -- Timestamps
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_owner_contact (owner_user_id, contact_user_id),
    CONSTRAINT fk_roster_owner FOREIGN KEY (owner_user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_roster_contact FOREIGN KEY (contact_user_id) REFERENCES users(user_id) ON DELETE SET NULL
);

CREATE INDEX idx_roster_owner ON roster_items(owner_user_id);
CREATE INDEX idx_roster_contact ON roster_items(contact_user_id);
CREATE INDEX idx_roster_jid ON roster_items(contact_jid);

-- ============================================================================
-- OFFLINE MESSAGES
-- ============================================================================
CREATE TABLE offline_messages (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id        VARCHAR(32) NOT NULL,
    from_jid            VARCHAR(255) NOT NULL,
    to_user_id          VARCHAR(32) NOT NULL,
    to_jid              VARCHAR(255) NOT NULL,
    stanza_id           VARCHAR(255) NOT NULL,
    -- Full stanza XML stored for accurate delivery with all extensions
    stanza_xml          TEXT NOT NULL,
    -- Extracted body for push notification preview
    body_preview        VARCHAR(500),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Push notification sent for this message?
    push_sent           BOOLEAN NOT NULL DEFAULT FALSE,
    push_sent_at        TIMESTAMP NULL,
    CONSTRAINT fk_from_user FOREIGN KEY (from_user_id) REFERENCES users(user_id),
    CONSTRAINT fk_to_user   FOREIGN KEY (to_user_id)   REFERENCES users(user_id)
);

CREATE INDEX idx_offline_to_user ON offline_messages(to_user_id);
CREATE INDEX idx_offline_push ON offline_messages(push_sent, to_user_id);

CREATE TABLE offline_messages_old (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- XMPP identity (internal IDs, not JIDs)
    message_id VARCHAR(64) NOT NULL,
    stanza_id VARCHAR(255) NULL,

    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,

    message_type VARCHAR(20) NOT NULL,

    -- E2EE payload (server cannot decrypt)
    encrypted_content TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    encrypted_metadata TEXT NULL,

    -- media / attachment support
    media_storage_key VARCHAR(255) NULL,
    mime_type VARCHAR(100) NULL,
    file_size_bytes BIGINT DEFAULT 0,

    -- XMPP threading (protocol-level, NOT DB ID)
    reply_to_message_id VARCHAR(64) NULL,

    -- delivery state
    status ENUM('pending','delivered','failed') DEFAULT 'pending',
    was_offline BOOLEAN DEFAULT TRUE,

    -- lifecycle
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,

    -- constraints
    UNIQUE KEY uk_message_id (message_id),

    -- performance indexes (critical for XMPP queues)
    INDEX idx_to_user_status (to_user_id, status),
    INDEX idx_from_user (from_user_id),
    INDEX idx_created_at (created_at),
    INDEX idx_stanza_id (stanza_id),

    -- optional FK (can remove at scale for performance)
    CONSTRAINT fk_offline_from_user
        FOREIGN KEY (from_user_id) REFERENCES users(user_id),

    CONSTRAINT fk_offline_to_user
        FOREIGN KEY (to_user_id) REFERENCES users(user_id)

) ENGINE=InnoDB;


CREATE TABLE offline_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- XMPP identity (internal IDs, not JIDs)
    message_id VARCHAR(64) NOT NULL,

    from_user_id VARCHAR(20) NOT NULL,
    to_user_id  VARCHAR(20) NOT NULL,

    message_type VARCHAR(20) NOT NULL,

    -- E2EE payload (server cannot decrypt)
    encrypted_content TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,

    encrypted_metadata TEXT NULL,

    -- media / attachment support
    media_storage_key VARCHAR(255) NULL,
    mime_type VARCHAR(100) NULL,
    file_size_bytes BIGINT DEFAULT 0,

    -- XMPP threading (protocol-level, NOT DB ID)
    reply_to_message_id VARCHAR(64) NULL,

    -- delivery state
    status ENUM('pending','delivered','failed') DEFAULT 'pending',
    was_offline BOOLEAN DEFAULT TRUE,

    -- lifecycle
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,

    -- constraints
    UNIQUE KEY uk_message_id (message_id),

    -- performance indexes (critical for XMPP queues)
    INDEX idx_to_user_status (to_user_id, status),
    INDEX idx_from_user (from_user_id),
    INDEX idx_created_at (created_at),

    -- optional FK (can remove at scale for performance)
    CONSTRAINT fk_offline_from_user
        FOREIGN KEY (from_user_id) REFERENCES users(user_id),

    CONSTRAINT fk_offline_to_user
        FOREIGN KEY (to_user_id) REFERENCES users(user_id)

) ENGINE=InnoDB;

-- ============================================================================
-- STREAM MANAGEMENT SESSIONS (XEP-0198)
-- ============================================================================
CREATE TABLE sm_sessions (
    sm_id               VARCHAR(64) PRIMARY KEY,
    user_id             VARCHAR(32) NOT NULL,
    unacked_stanzas     JSON,
    client_acked        BIGINT NOT NULL DEFAULT 0,
    server_sent         BIGINT NOT NULL DEFAULT 0,
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sm_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_sm_user ON sm_sessions(user_id);

-- ============================================================================
-- AUDIT LOG
-- ============================================================================

CREATE TABLE audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type      VARCHAR(64) NOT NULL,
    user_id         VARCHAR(32),
    details         JSON,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE INDEX idx_audit_user ON audit_log(user_id, created_at);
CREATE INDEX idx_audit_type ON audit_log(event_type, created_at);

-- ============================================================================
-- MESSAGE ARCHIVE (XEP-0313 - optional but useful)
-- ============================================================================

CREATE TABLE message_archive (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    archive_id          CHAR(36) NOT NULL UNIQUE DEFAULT (UUID()),
    owner_user_id       VARCHAR(32) NOT NULL,
    from_jid            VARCHAR(255) NOT NULL,
    to_jid              VARCHAR(255) NOT NULL,
    stanza_id           VARCHAR(255),
    body                TEXT,
    stanza_xml          TEXT NOT NULL,
    timestamp           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_archive_owner FOREIGN KEY (owner_user_id) REFERENCES users(user_id)
);

CREATE INDEX idx_archive_owner ON message_archive(owner_user_id, timestamp);

CREATE INDEX idx_archive_stanza_id ON message_archive(stanza_id);

-- Privacy settings
CREATE TABLE user_privacy (
    user_id VARCHAR(32) PRIMARY KEY,

    -- everyone | contacts | nobody
    last_seen_visibility VARCHAR(20) NOT NULL DEFAULT 'contacts',

    -- everyone | contacts | nobody
    photo_visibility VARCHAR(20) NOT NULL DEFAULT 'contacts',

    -- everyone | contacts | nobody
    about_visibility VARCHAR(20) NOT NULL DEFAULT 'contacts',

    -- true = enabled, false = disabled
    read_receipts_enabled BOOLEAN NOT NULL DEFAULT TRUE,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_privacy_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
);

-- Track which phone numbers each user has saved
CREATE TABLE phonebook_uploads (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    user_id VARCHAR(32) NOT NULL,

    phone_hash VARCHAR(64) NOT NULL,

    contact_name VARCHAR(100),

    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_phonebook_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE CASCADE,

    UNIQUE KEY uq_user_phone (user_id, phone_hash)
);

CREATE INDEX idx_phonebook_user
    ON phonebook_uploads(user_id);

CREATE INDEX idx_phonebook_hash
    ON phonebook_uploads(phone_hash);

-- ===============================
--          CALL RECORD
-- ===============================
CREATE TABLE call_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_id CHAR(36) NOT NULL DEFAULT (UUID()) UNIQUE,

    twilio_room_sid VARCHAR(64) UNIQUE,

    caller_user_id VARCHAR(32) NOT NULL,
    callee_user_id VARCHAR(32) NOT NULL,

    call_type VARCHAR(20) NOT NULL,

    state VARCHAR(20) NOT NULL DEFAULT 'ringing',

    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP NULL,
    ended_at TIMESTAMP NULL,

    duration_seconds INT,
    end_reason VARCHAR(30),

    CONSTRAINT fk_caller FOREIGN KEY (caller_user_id) REFERENCES users(user_id),
    CONSTRAINT fk_callee FOREIGN KEY (callee_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE INDEX idx_calls_users ON call_records(caller_user_id, callee_user_id);


-- ========================
--      BLOCK TABLE
-- ========================
CREATE TABLE user_blocks (
    blocker_jid  VARCHAR(255) NOT NULL,
    blocked_jid  VARCHAR(255) NOT NULL,

    reason  VARCHAR(255) NULL,

    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (blocker_jid, blocked_jid),
    INDEX idx_blocked_jid (blocked_jid)
);

-- ====================================================
--                  GROUP LINKS
-- ====================================================

> CREATE TABLE invite_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id VARCHAR(64) NOT NULL,
    token VARCHAR(10) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NULL,
    max_uses INT NULL,
    use_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_group FOREIGN KEY (group_id) REFERENCES chat_groups(group_id));



-- ============================================================================
-- GROUPS (the rooms themselves)
-- ============================================================================
CREATE TABLE `groups` (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    group_id            CHAR(36) NOT NULL UNIQUE,
    jid                 VARCHAR(255) NOT NULL UNIQUE,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    avatar_url          VARCHAR(500),
    creator_user_id     VARCHAR(32) NOT NULL,
    state               ENUM('unlocked','locked','destroyed') NOT NULL DEFAULT 'unlocked',
    visibility          ENUM('public','private','hidden') NOT NULL DEFAULT 'private',
    persistent          BOOLEAN NOT NULL DEFAULT TRUE,
    max_members         INT UNSIGNED NOT NULL DEFAULT 256,
    member_count        INT UNSIGNED NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at          TIMESTAMP(6) NULL,

    INDEX idx_groups_creator (creator_user_id),
    INDEX idx_groups_state (state, deleted_at),
    INDEX idx_groups_visibility (visibility, state)
) ENGINE=InnoDB
  PARTITION BY HASH(id) PARTITIONS 16;

-- ============================================================================
-- GROUP MEMBERS (affiliations - persistent membership)
-- ============================================================================
CREATE TABLE group_members (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    group_id            CHAR(36) NOT NULL,
    user_id             VARCHAR(32) NOT NULL,
    user_jid            VARCHAR(255) NOT NULL,
    affiliation         ENUM('owner','admin','member','outcast') NOT NULL DEFAULT 'member',
    nickname            VARCHAR(100),
    joined_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_active_at      TIMESTAMP(6),
    invited_by_user_id  VARCHAR(32),
    muted_until         TIMESTAMP(6) NULL,
    left_at             TIMESTAMP(6) NULL,
    removed_by_user_id  VARCHAR(32),

    UNIQUE KEY uq_group_user (group_id, user_id),
    INDEX idx_members_user (user_id, left_at),
    INDEX idx_members_group (group_id, left_at),
    INDEX idx_members_affiliation (group_id, affiliation, left_at),

    CONSTRAINT fk_members_group FOREIGN KEY (group_id)
        REFERENCES `groups`(group_id) ON DELETE CASCADE
) ENGINE=InnoDB
  PARTITION BY KEY(group_id) PARTITIONS 16;

-- ============================================================================
-- GROUP SETTINGS
-- ============================================================================
CREATE TABLE group_settings (
    group_id                    CHAR(36) PRIMARY KEY,
    only_admins_can_send        BOOLEAN NOT NULL DEFAULT FALSE,
    only_admins_can_edit_meta   BOOLEAN NOT NULL DEFAULT TRUE,
    only_admins_can_add         BOOLEAN NOT NULL DEFAULT FALSE,
    membership_approval         BOOLEAN NOT NULL DEFAULT FALSE,
    announcement_mode           BOOLEAN NOT NULL DEFAULT FALSE,
    allow_history               BOOLEAN NOT NULL DEFAULT TRUE,
    history_max_messages        INT UNSIGNED NOT NULL DEFAULT 50,
    disappearing_seconds        INT UNSIGNED NOT NULL DEFAULT 0,
    updated_at                  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                    ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by_user_id          VARCHAR(32),

    CONSTRAINT fk_settings_group FOREIGN KEY (group_id)
        REFERENCES `groups`(group_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================================
-- GROUP INVITATIONS (direct invites)
-- ============================================================================
CREATE TABLE group_invitations (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    invitation_id       CHAR(36) NOT NULL UNIQUE,
    group_id            CHAR(36) NOT NULL,
    inviter_user_id     VARCHAR(32) NOT NULL,
    invitee_user_id     VARCHAR(32) NOT NULL,
    invitee_jid         VARCHAR(255) NOT NULL,
    reason              VARCHAR(500),
    state               ENUM('pending','accepted','rejected','expired','revoked')
                            NOT NULL DEFAULT 'pending',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    responded_at        TIMESTAMP(6) NULL,
    expires_at          TIMESTAMP(6) NOT NULL,

    UNIQUE KEY uq_invitation_pending (group_id, invitee_user_id, state),
    INDEX idx_invitations_invitee (invitee_user_id, state),
    INDEX idx_invitations_expires (expires_at, state),

    CONSTRAINT fk_invitations_group FOREIGN KEY (group_id)
        REFERENCES `groups`(group_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================================
-- GROUP JOIN LINKS (WhatsApp-style invite links)
-- ============================================================================
CREATE TABLE group_join_links (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    link_token          VARCHAR(64) NOT NULL UNIQUE,
    group_id            CHAR(36) NOT NULL,
    created_by_user_id  VARCHAR(32) NOT NULL,
    one_time            BOOLEAN NOT NULL DEFAULT FALSE,
    used                BOOLEAN NOT NULL DEFAULT FALSE,
    used_by_user_id     VARCHAR(32),
    use_count           INT UNSIGNED NOT NULL DEFAULT 0,
    max_uses            INT UNSIGNED,
    expires_at          TIMESTAMP(6) NULL,
    revoked             BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at          TIMESTAMP(6) NULL,
    revoked_by_user_id  VARCHAR(32),
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_links_group (group_id, revoked),
    INDEX idx_links_expires (expires_at, revoked),

    CONSTRAINT fk_links_group FOREIGN KEY (group_id)
        REFERENCES `groups`(group_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================================
-- GROUP MESSAGE HISTORY
-- ============================================================================
CREATE TABLE group_message_history (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    message_id          CHAR(36) NOT NULL,
    group_id            CHAR(36) NOT NULL,
    sender_user_id      VARCHAR(32) NOT NULL,
    sender_jid          VARCHAR(255) NOT NULL,
    message_type        VARCHAR(20) NOT NULL DEFAULT 'text',
    encrypted_content   TEXT NOT NULL,
    iv                  VARCHAR(32) NOT NULL,
    media_storage_key   VARCHAR(500),
    mime_type           VARCHAR(100),
    file_size_bytes     BIGINT UNSIGNED,
    reply_to_message_id CHAR(36),
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at          TIMESTAMP(6) NULL,

    UNIQUE KEY uq_message_id (message_id),
    INDEX idx_group_history (group_id, created_at, deleted_at),
    INDEX idx_sender_history (sender_user_id, created_at)
) ENGINE=InnoDB
  PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
    PARTITION p_2024 VALUES LESS THAN (UNIX_TIMESTAMP('2025-01-01')),
    PARTITION p_2025 VALUES LESS THAN (UNIX_TIMESTAMP('2026-01-01')),
    PARTITION p_2026 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- ============================================================================
-- GROUP EVENTS (state transitions for audit)
-- ============================================================================
CREATE TABLE group_events (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    event_id        CHAR(36) NOT NULL UNIQUE,
    group_id        CHAR(36) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    actor_user_id   VARCHAR(32),
    target_user_id  VARCHAR(32),
    payload         JSON,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_events_group (group_id, created_at),
    INDEX idx_events_type (event_type, created_at)
) ENGINE=InnoDB;

-- ============================================================================
-- AUDIT LOG (security-sensitive ops)
-- ============================================================================
CREATE TABLE group_audit_log (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    group_id        CHAR(36),
    actor_user_id   VARCHAR(32),
    action          VARCHAR(50) NOT NULL,
    target_user_id  VARCHAR(32),
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(255),
    details         JSON,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_audit_actor (actor_user_id, created_at),
    INDEX idx_audit_group (group_id, created_at)
) ENGINE=InnoDB;


-- Track current generation per group
CREATE TABLE group_sender_key_generations (
    group_id        CHAR(36) PRIMARY KEY,
    generation      INT UNSIGNED NOT NULL DEFAULT 1,
    rotated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reason          VARCHAR(50),
    trigger_jid     VARCHAR(255),

    INDEX idx_rotated_at (rotated_at)
) ENGINE=InnoDB;

-- Store rotation events for offline delivery
CREATE TABLE group_sender_key_events (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    group_id        CHAR(36) NOT NULL,
    generation      INT UNSIGNED NOT NULL,
    reason          VARCHAR(50),
    member_list     TEXT NOT NULL,         -- comma-separated JIDs
    stanza_xml      TEXT NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      TIMESTAMP(6) NOT NULL,

    INDEX idx_events_group (group_id, created_at),
    INDEX idx_events_expires (expires_at)
) ENGINE=InnoDB;



CREATE TABLE events (
    -- The Core Engine: ULID/Snowflake ID handles chronological sorting globally
    event_id CHAR(26) NOT NULL,

    -- Routing Discriminators
    event_category ENUM('chat', 'groupchat', 'system') NOT NULL,
    event_type VARCHAR(30) NOT NULL,

    -- Context Properties (Polymorphic references)
    group_id CHAR(36) DEFAULT NULL,      -- NULL for 1-to-1 messages
    sender_id VARCHAR(32) NOT NULL,      -- Equal to from_user_id or system_actor
    recipient_id VARCHAR(32) DEFAULT NULL,-- Used for 1-to-1 chats (to_user_id)
    sender_jid VARCHAR(255) DEFAULT NULL,

    -- Cryptographic Guardrails
    group_version INT DEFAULT NULL,      -- Used strictly for GROUP_MSG and GROUP_EVENT
    epoch INT DEFAULT NULL,              -- For E2EE key management rotation windows
    iv VARCHAR(64) NOT NULL,             -- Cryptographic initialization vector

    -- Shared Payload Content
    encrypted_content TEXT NOT NULL,     -- The main core ciphertext
    encrypted_metadata TEXT DEFAULT NULL, -- Structured JSON blobs for replies, system info, etc.
    reply_to_event_id CHAR(26) DEFAULT NULL, -- Points to parent event_id

    -- Media Attachments
    media_storage_key VARCHAR(500) DEFAULT NULL,
    mime_type VARCHAR(100) DEFAULT NULL,
    file_size_bytes BIGINT UNSIGNED DEFAULT NULL,

    -- Housekeeping & Ephemerality
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) DEFAULT NULL, -- Enforces the WhatsApp-style 30-day eviction

    PRIMARY KEY (event_id)
);

-- Lookups for fetching history relative to a specific group's timeline bounds
CREATE INDEX idx_events_group_stream ON events(group_id, event_id ASC);

-- Lookups for pulling down a user's pending direct messages
CREATE INDEX idx_events_direct_stream ON events(recipient_id, event_id ASC) WHERE event_category = 'chat';

-- Fast scans for background background worker garbage collection (TTL eviction)
CREATE INDEX idx_events_expiry ON events(expires_at);