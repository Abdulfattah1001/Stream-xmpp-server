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