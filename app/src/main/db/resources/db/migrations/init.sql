--- =====================================================================
--  XMPP USERS TABLES
--- ===================================================================

CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(32) NOT NULL,
  username VARCHAR(100) NOT NULL,
  jid VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) DEFAULT NULL,
  display_status VARCHAR(100) DEFAULT NULL,
  avatar_url VARCHAR(500) DEFAULT NULL,
  metadata JSON DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT '0',
  active TINYINT(1) NOT NULL DEFAULT '1',
  phone_verified TINYINT(1) NOT NULL DEFAULT '0',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_seen TIMESTAMP NULL DEFAULT NULL,
  deleted_at TIMESTAMP NULL DEFAULT NULL,
  phone_region CHAR(2) DEFAULT NULL,
  phone_hmac CHAR(66) NOT NULL DEFAULT '',
  phone_enc VARBINARY(512) DEFAULT NULL,
  phone_verified_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY user_id (user_id),
  UNIQUE KEY jid (jid),
  KEY idx_users_jid (jid),
  KEY idx_users_active (active)
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE INDEX idx_users_firebase_uid ON users(firebase_uid);
CREATE INDEX idx_users_phone_hash ON users(phone_number_hash);
CREATE INDEX idx_users_jid ON users(jid);
CREATE INDEX idx_users_active ON users(active);


CREATE TABLE contact_edges (
  owner_user_id CHAR(14) NOT NULL,
  contact_user_id CHAR(14) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (owner_user_id,contact_user_id),
  KEY idx_edges_reverse (contact_user_id),
  CONSTRAINT fk_edges_contact FOREIGN KEY (contact_user_id) REFERENCES users (user_id) ON DELETE CASCADE,
  CONSTRAINT fk_edges_owner FOREIGN KEY (owner_user_id) REFERENCES users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE contact_discovery (
  salt_version SMALLINT unsigned NOT NULL,
  discovery_hash VARBINARY(32) NOT NULL,
  user_id CHAR(14) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (salt_version,discovery_hash),
  KEY idx_discovery_user (user_id),
  CONSTRAINT fk_discovery_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================================
-- SESSION TOKENS
-- Replaces passwords entirely.
-- A session token is issued after Firebase auth verification.
-- Used for XMPP SASL authentication instead of a password.
-- ============================================================================


CREATE TABLE session_tokens (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(255) NOT NULL DEFAULT '',
  `user_id` VARCHAR(32) NOT NULL,
  `token_hash` VARCHAR(64) NOT NULL,
  `device_label` VARCHAR(255) DEFAULT NULL,
  `device_id` VARCHAR(255) NOT NULL DEFAULT '',
  `push_token` VARCHAR(500) DEFAULT NULL,
  `platform` VARCHAR(20) DEFAULT NULL,
  `app_version` VARCHAR(20) DEFAULT NULL,
  `expires_at` DATETIME DEFAULT NULL,
  `last_used_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_from_ip` VARCHAR(45) DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `revoked_at` TIMESTAMP NULL DEFAULT NULL,
  `revoked_reason` VARCHAR(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `token_hash` (`token_hash`),
  KEY `idx_session_tokens_user_id` (`user_id`),
  KEY `idx_session_tokens_hash` (`token_hash`),
  KEY `idx_session_tokens_expires` (`expires_at`,`revoked_at`),
  KEY `idx_session_tokens_push` (`push_token`,`revoked_at`),
  CONSTRAINT `fk_session_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci

CREATE INDEX idx_session_tokens_user_id  ON session_tokens(user_id);
CREATE INDEX idx_session_tokens_hash ON session_tokens(token_hash);
CREATE INDEX idx_session_tokens_expires ON session_tokens(expires_at, revoked_at);
CREATE INDEX idx_session_tokens_push ON session_tokens(push_token, revoked_at);

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
-- DEVICE REGISTRY
-- Tracks all devices a user has ever logged in from
-- ============================================================================
CREATE TABLE `devices` (
  `id` BIGINT unsigned NOT NULL AUTO_INCREMENT,
  `user_id` CHAR(32) NOT NULL,
  `device_id` VARCHAR(128) NOT NULL,
  `kind` ENUM('primary','companion') NOT NULL DEFAULT 'primary',
  `device_label` VARCHAR(120) DEFAULT NULL,
  `platform` ENUM('android','ios','web','desktop') NOT NULL,
  `app_version` VARCHAR(32) DEFAULT NULL,
  `push_token` VARCHAR(512) DEFAULT NULL,
  `push_token_updated_at` DATETIME DEFAULT NULL,
  `last_seen_ip` VARBINARY(16) DEFAULT NULL,
  `last_seen_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_devices_user_device` (`user_id`,`device_id`),
  KEY `idx_devices_push` (`user_id`,`push_token`),
  CONSTRAINT `fk_devices_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=30 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci

CREATE TABLE `offline_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` VARCHAR(64) NOT NULL,
  `group_id` VARCHAR(40) DEFAULT NULL,
  `from_user_id` VARCHAR(32) NOT NULL,
  `to_user_id` VARCHAR(32) NOT NULL,
  `message_type` VARCHAR(20) NOT NULL,
  `encrypted_content` TEXT NOT NULL,
  `encrypted_metadata` TEXT,
  `media_storage_key` VARCHAR(255) DEFAULT NULL,
  `mime_type` VARCHAR(100) DEFAULT NULL,
  `file_size_bytes` BIGINT DEFAULT '0',
  `reply_to_message_id` VARCHAR(64) DEFAULT NULL,
  `status` ENUM('pending','delivered','failed') DEFAULT 'pending',
  `was_offline` TINYINT(1) DEFAULT '1',
  `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` TIMESTAMP NULL DEFAULT NULL,
  `system_event_type` VARCHAR(50) DEFAULT NULL,
  `system_actor` VARCHAR(20) DEFAULT NULL,
  `system_subject` VARCHAR(20) DEFAULT NULL,
  `system_source` VARCHAR(30) DEFAULT NULL,
  `type` VARCHAR(20) NOT NULL DEFAULT 'chat',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_to_user_status` (`to_user_id`,`status`),
  KEY `idx_from_user` (`from_user_id`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `fk_offline_from_user` FOREIGN KEY (`from_user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_offline_to_user` FOREIGN KEY (`to_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=134 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci



CREATE TABLE `pending_receipts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `from_uid` VARCHAR(32) NOT NULL,
  `to_uid` VARCHAR(32) NOT NULL,
  `message_id` VARCHAR(255) NOT NULL,
  `receipt_type` ENUM('received','displayed','server_received') NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pending_receipts_user` (`to_uid`),
  CONSTRAINT `pending_receipts_ibfk_1` FOREIGN KEY (`to_uid`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=82 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
CREATE INDEX idx_pending_receipts_user ON pending_receipts(to_uid);


CREATE TABLE `profile_change_seq` (
  `id` SMALLINT NOT NULL,
  `next_seq` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `profile_change_seq_one` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Initialize the singleton tracking row at sequence 0
INSERT INTO profile_change_seq (id, next_seq) VALUES (1, 0)


CREATE TABLE `changes` (
  `seq` BIGINT NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `version` VARCHAR NOT NULL,
  `field_mask` INT NOT NULL,
  `changed_at` TIMESTAMP NOT NULL,
  PRIMARY KEY (`seq`),
  UNIQUE KEY `changes_user_version` (`user_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- DEVICE KEYS (for multi-device E2E encryption)
-- Each device has its own encryption keys
-- Signal Protocol: one key bundle per device
-- ============================================================================

CREATE TABLE `signal_one_time_keys` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` VARCHAR(32) NOT NULL,
  `android_id` VARCHAR(32) NOT NULL,
  `key_id` INT NOT NULL,
  `public_key` TEXT NOT NULL,
  `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  `device_id` INT unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci

CREATE TABLE `signal_identity_bundles` (
  `user_id` VARCHAR(32) NOT NULL,
  `registration_id` INT NOT NULL,
  `android_id` VARCHAR(32) NOT NULL,
  `identity_key` TEXT NOT NULL,
  `signed_key_id` INT NOT NULL,
  `signed_key` TEXT NOT NULL,
  `signature` TEXT NOT NULL,
  `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `device_id` INT unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci