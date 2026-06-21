-- Users table
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(255) NOT NULL UNIQUE,
    contact_id      VARCHAR(255) NOT NULL UNIQUE, -- bare JID: user@domain
    password_hash   VARCHAR(255) NOT NULL,         -- BCrypt hash
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen       TIMESTAMPTZ,
    avatar_hash     VARCHAR(255)
);

-- Roster (contact list)
CREATE TABLE roster_items (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_jid     VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    subscription    VARCHAR(16) NOT NULL DEFAULT 'none', -- none|from|to|both|remove
    ask             VARCHAR(16),                          -- subscribe (pending outbound)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(owner_id, contact_jid)
);

-- Roster groups (folders)
CREATE TABLE roster_groups (
    id              BIGSERIAL PRIMARY KEY,
    roster_item_id  BIGINT NOT NULL REFERENCES roster_items(id) ON DELETE CASCADE,
    group_name      VARCHAR(255) NOT NULL
);

-- Offline message storage
CREATE TABLE offline_messages (
    id              BIGSERIAL PRIMARY KEY,
    from_jid        VARCHAR(255) NOT NULL,
    to_contact_id   VARCHAR(255) NOT NULL,
    stanza_id       VARCHAR(255),
    body            TEXT NOT NULL,
    full_stanza     TEXT,          -- Store full XML for XEP-0203 delayed delivery
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_offline_to ON offline_messages(to_contact_id);

-- Message archive (XEP-0313)
CREATE TABLE message_archive (
    id              BIGSERIAL PRIMARY KEY,
    archive_id      VARCHAR(255) NOT NULL UNIQUE,  -- UUID for MAM queries
    owner_jid       VARCHAR(255) NOT NULL,
    from_jid        VARCHAR(255) NOT NULL,
    to_jid          VARCHAR(255) NOT NULL,
    body            TEXT,
    full_stanza     TEXT NOT NULL,
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_archive_owner     ON message_archive(owner_jid, timestamp);
CREATE INDEX idx_archive_owner_jid ON message_archive(owner_jid, from_jid, timestamp);

-- Pending subscription requests
CREATE TABLE subscription_requests (
    id              BIGSERIAL PRIMARY KEY,
    from_jid        VARCHAR(255) NOT NULL,
    to_contact_id   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(from_jid, to_contact_id)
);

-- Stream management state (XEP-0198 resumption)
CREATE TABLE sm_sessions (
    sm_id           VARCHAR(255) PRIMARY KEY,
    contact_id      VARCHAR(255) NOT NULL,
    unacked_stanzas JSONB,
    client_acked    BIGINT NOT NULL DEFAULT 0,
    server_sent     BIGINT NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sm_contact ON sm_sessions(contact_id);

-- Active sessions (for clustering - optional)
CREATE TABLE active_sessions (
    uid             VARCHAR(255) PRIMARY KEY,
    contact_id      VARCHAR(255) NOT NULL,
    resource        VARCHAR(255),
    node_id         VARCHAR(255) NOT NULL,  -- Which server node owns this session
    priority        INT NOT NULL DEFAULT 0,
    presence_type   VARCHAR(32),
    presence_show   VARCHAR(16),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_active_contact ON active_sessions(contact_id);

-- Admin audit log
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(64) NOT NULL,
    actor_jid       VARCHAR(255),
    target_jid      VARCHAR(255),
    details         JSONB,
    ip_address      INET,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- GROUP MESSAGING
-- ============================================================================

CREATE TABLE groups (
    id                  BIGSERIAL PRIMARY KEY,
    group_id            VARCHAR(32) NOT NULL UNIQUE,  -- g_7f3a9b2c
    jid                 VARCHAR(255) NOT NULL UNIQUE,  -- g_7f3a9b2c@conference.domain.com
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    avatar_url          VARCHAR(500),

    -- Group type: standard | broadcast | channel
    group_type          VARCHAR(20) NOT NULL DEFAULT 'standard',

    -- Who created the group
    creator_user_id     VARCHAR(32) NOT NULL REFERENCES users(user_id),

    -- Invite settings: anyone | admin_only
    invite_mode         VARCHAR(20) NOT NULL DEFAULT 'anyone',

    -- Max members
    max_members         INT NOT NULL DEFAULT 256,

    -- Group link for sharing (null = invite only)
    invite_link         VARCHAR(64) UNIQUE,
    invite_link_enabled BOOLEAN NOT NULL DEFAULT false,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_groups_jid ON groups(jid);

CREATE TABLE group_members (
    id                  BIGSERIAL PRIMARY KEY,
    group_id            VARCHAR(32) NOT NULL REFERENCES groups(group_id)
                            ON DELETE CASCADE,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id)
                            ON DELETE CASCADE,

    -- Role: member | admin | owner
    role                VARCHAR(20) NOT NULL DEFAULT 'member',

    -- Nickname in this group
    nickname            VARCHAR(100),

    -- Muted until (null = not muted, past date = unmuted)
    muted_until         TIMESTAMPTZ,

    joined_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at             TIMESTAMPTZ,
    removed_by          VARCHAR(32) REFERENCES users(user_id),

    UNIQUE(group_id, user_id)
);

CREATE INDEX idx_group_members_group ON group_members(group_id)
    WHERE left_at IS NULL;
CREATE INDEX idx_group_members_user  ON group_members(user_id)
    WHERE left_at IS NULL;

-- ============================================================================
-- MESSAGE ENHANCEMENTS
-- ============================================================================

-- Message reactions (any emoji, custom reactions)
CREATE TABLE message_reactions (
    id                  BIGSERIAL PRIMARY KEY,
    message_id          UUID NOT NULL REFERENCES messages(message_id)
                            ON DELETE CASCADE,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id),
    reaction            VARCHAR(64) NOT NULL,  -- emoji or custom reaction ID
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(message_id, user_id, reaction)
);

CREATE INDEX idx_reactions_message ON message_reactions(message_id);

-- Message edits (full edit history)
CREATE TABLE message_edits (
    id                  BIGSERIAL PRIMARY KEY,
    message_id          UUID NOT NULL REFERENCES messages(message_id)
                            ON DELETE CASCADE,
    -- The new encrypted content after edit
    encrypted_content   TEXT NOT NULL,
    iv                  VARCHAR(32) NOT NULL,
    edited_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    edit_number         INT NOT NULL DEFAULT 1
);

CREATE INDEX idx_edits_message ON message_edits(message_id);

-- Scheduled messages
--- POSTGRESQL
CREATE TABLE scheduled_messages (
    id                  BIGSERIAL PRIMARY KEY,
    message_id          UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    from_user_id        VARCHAR(32) NOT NULL REFERENCES users(user_id),
    to_jid              VARCHAR(255) NOT NULL,
    message_type        VARCHAR(20) NOT NULL DEFAULT 'text',
    encrypted_content   TEXT NOT NULL,
    iv                  VARCHAR(32) NOT NULL,
    media_storage_key   VARCHAR(500),
    mime_type           VARCHAR(100),
    scheduled_for       TIMESTAMPTZ NOT NULL,
    sent_at             TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

--- MySQL
CREATE TABLE scheduled_messages (
       id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
       message_id          CHAR(36) NOT NULL UNIQUE DEFAULT (UUID()),
       from_user_id        VARCHAR(32) NOT NULL,
       to_jid              VARCHAR(255) NOT NULL,
       message_type        VARCHAR(20) NOT NULL DEFAULT 'text',
       encrypted_content   TEXT NOT NULL,
       iv                  VARCHAR(32) NOT NULL,
       media_storage_key   VARCHAR(500),
       mime_type           VARCHAR(100),
       scheduled_for       TIMESTAMP NOT NULL,
       sent_at             TIMESTAMP NULL,
       cancelled_at        TIMESTAMP NULL,
       created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

       CONSTRAINT fk_user
           FOREIGN KEY (from_user_id) REFERENCES users(user_id)
   );

CREATE INDEX idx_scheduled_pending ON scheduled_messages(scheduled_for)
    WHERE sent_at IS NULL AND cancelled_at IS NULL;

-- Polls
CREATE TABLE polls (
    id                  BIGSERIAL PRIMARY KEY,
    poll_id             UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    message_id          UUID REFERENCES messages(message_id),
    creator_user_id     VARCHAR(32) NOT NULL REFERENCES users(user_id),
    question            VARCHAR(500) NOT NULL,
    -- multiple_choice: can vote for more than one option
    multiple_choice     BOOLEAN NOT NULL DEFAULT false,
    -- anonymous: voters are not shown
    anonymous           BOOLEAN NOT NULL DEFAULT false,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE poll_options (
    id                  BIGSERIAL PRIMARY KEY,
    poll_id             UUID NOT NULL REFERENCES polls(poll_id)
                            ON DELETE CASCADE,
    option_text         VARCHAR(200) NOT NULL,
    display_order       INT NOT NULL DEFAULT 0
);

CREATE TABLE poll_votes (
    id                  BIGSERIAL PRIMARY KEY,
    poll_id             UUID NOT NULL REFERENCES polls(poll_id)
                            ON DELETE CASCADE,
    option_id           BIGINT NOT NULL REFERENCES poll_options(id)
                            ON DELETE CASCADE,
    voter_user_id       VARCHAR(32) NOT NULL REFERENCES users(user_id),
    voted_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(poll_id, option_id, voter_user_id)
);

-- Disappearing messages settings (per conversation)
CREATE TABLE disappearing_message_settings (
    id                  BIGSERIAL PRIMARY KEY,
    -- For DM: the two user IDs (smaller first for consistency)
    user_id_a           VARCHAR(32) NOT NULL REFERENCES users(user_id),
    user_id_b           VARCHAR(32) REFERENCES users(user_id),
    -- For group:
    group_id            VARCHAR(32) REFERENCES groups(group_id),

    -- Duration in seconds: 0=off, 86400=1day, 604800=1week, 2592000=30days
    duration_seconds    INT NOT NULL DEFAULT 0,
    set_by_user_id      VARCHAR(32) NOT NULL REFERENCES users(user_id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT disappearing_target CHECK (
        (user_id_b IS NOT NULL AND group_id IS NULL) OR
        (user_id_b IS NULL AND group_id IS NOT NULL)
    )
);

-- ============================================================================
-- PUSH NOTIFICATIONS
-- ============================================================================

CREATE TABLE push_notifications_log (
    id                  BIGSERIAL PRIMARY KEY,
    to_user_id          VARCHAR(32) NOT NULL REFERENCES users(user_id),
    push_token          VARCHAR(500) NOT NULL,
    platform            VARCHAR(20) NOT NULL,  -- android | ios
    -- Notification type: message | call | group_invite | status
    notification_type   VARCHAR(30) NOT NULL,
    -- Reference to the triggering entity
    reference_id        VARCHAR(255),
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- FCM/APNs response
    success             BOOLEAN NOT NULL DEFAULT false,
    error_code          VARCHAR(100),
    -- De-duplicate: don't send twice for same event
    idempotency_key     VARCHAR(255) UNIQUE
);

CREATE INDEX idx_push_log_user ON push_notifications_log(to_user_id, sent_at);

-- ============================================================================
-- VOICE/VIDEO CALLS
-- ============================================================================

CREATE TABLE calls (
    id                  BIGSERIAL PRIMARY KEY,
    call_id             UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    caller_user_id      VARCHAR(32) NOT NULL REFERENCES users(user_id),
    callee_user_id      VARCHAR(32) REFERENCES users(user_id),
    group_id            VARCHAR(32) REFERENCES groups(group_id),

    -- Type: voice | video | group_voice | group_video
    call_type           VARCHAR(20) NOT NULL,

    -- State: ringing | answered | declined | missed | ended | failed
    state               VARCHAR(20) NOT NULL DEFAULT 'ringing',

    -- WebRTC signaling (ICE candidates, SDP offers stored as JSON)
    signaling_data      JSONB,

    started_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    answered_at         TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,

    -- Duration in seconds (null if not answered)
    duration_seconds    INT,

    -- End reason: completed | declined | missed | failed | busy
    end_reason          VARCHAR(20)
);

CREATE INDEX idx_calls_caller ON calls(caller_user_id, started_at);
CREATE INDEX idx_calls_callee ON calls(callee_user_id, started_at);

-- ============================================================================
-- BLOGS (Medium-style, custom duration)
-- ============================================================================

CREATE TABLE blogs (
    id                  BIGSERIAL PRIMARY KEY,
    blog_id             UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    author_user_id      VARCHAR(32) NOT NULL REFERENCES users(user_id)
                            ON DELETE CASCADE,

    -- Blog content
    title               VARCHAR(300) NOT NULL,
    slug                VARCHAR(350) NOT NULL,  -- URL-friendly title

    -- Encrypted content (author controls encryption)
    -- For public blogs: not encrypted (null iv)
    -- For private/follower blogs: AES-256-GCM encrypted
    content_json        TEXT NOT NULL,  -- Structured content (blocks)
    encrypted           BOOLEAN NOT NULL DEFAULT false,
    content_iv          VARCHAR(32),    -- null if not encrypted

    -- Cover image
    cover_image_key     VARCHAR(500),
    cover_image_url     VARCHAR(500),

    -- SEO/Discovery
    summary             VARCHAR(500),  -- First 500 chars or custom summary
    tags                TEXT[],        -- searchable tags

    -- Reading time estimate (calculated on post)
    read_time_minutes   INT NOT NULL DEFAULT 1,

    -- Visibility: public | contacts | private
    visibility          VARCHAR(20) NOT NULL DEFAULT 'public',

    -- Publication state: draft | published | archived | deleted
    state               VARCHAR(20) NOT NULL DEFAULT 'draft',

    -- Custom duration (null = lives forever)
    -- Examples: 7 days for temporary announcements
    --           30 days for event posts
    --           null for permanent articles
    expires_at          TIMESTAMPTZ,

    -- Engagement
    view_count          BIGINT NOT NULL DEFAULT 0,
    like_count          INT NOT NULL DEFAULT 0,
    comment_count       INT NOT NULL DEFAULT 0,
    share_count         INT NOT NULL DEFAULT 0,

    -- Featured by admin
    featured            BOOLEAN NOT NULL DEFAULT false,
    featured_at         TIMESTAMPTZ,

    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ,

    UNIQUE(author_user_id, slug)
);

CREATE INDEX idx_blogs_author      ON blogs(author_user_id, state, published_at);
CREATE INDEX idx_blogs_public      ON blogs(state, published_at, visibility)
    WHERE state = 'published' AND deleted_at IS NULL;
CREATE INDEX idx_blogs_expires     ON blogs(expires_at)
    WHERE expires_at IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_blogs_tags        ON blogs USING GIN(tags);
CREATE INDEX idx_blogs_featured    ON blogs(featured, published_at)
    WHERE featured = true;

-- Blog content blocks (structured content like Notion/Medium)
CREATE TABLE blog_blocks (
    id                  BIGSERIAL PRIMARY KEY,
    blog_id             UUID NOT NULL REFERENCES blogs(blog_id)
                            ON DELETE CASCADE,
    block_id            UUID NOT NULL DEFAULT gen_random_uuid(),

    -- Block type: paragraph | heading1 | heading2 | heading3 |
    --             image | video | code | quote | divider |
    --             embed | list | callout
    block_type          VARCHAR(30) NOT NULL,

    -- Position in blog (0-based)
    position            INT NOT NULL DEFAULT 0,

    -- Content (JSON structure varies by block_type)
    content             JSONB NOT NULL,

    -- For media blocks
    media_storage_key   VARCHAR(500),
    media_url           VARCHAR(500),
    mime_type           VARCHAR(100),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(blog_id, block_id)
);

CREATE INDEX idx_blog_blocks ON blog_blocks(blog_id, position);

-- Blog likes
CREATE TABLE blog_likes (
    id                  BIGSERIAL PRIMARY KEY,
    blog_id             UUID NOT NULL REFERENCES blogs(blog_id)
                            ON DELETE CASCADE,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id),
    liked_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(blog_id, user_id)
);

-- Blog comments
CREATE TABLE blog_comments (
    id                  BIGSERIAL PRIMARY KEY,
    comment_id          UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    blog_id             UUID NOT NULL REFERENCES blogs(blog_id)
                            ON DELETE CASCADE,
    author_user_id      VARCHAR(32) NOT NULL REFERENCES users(user_id),
    parent_comment_id   UUID REFERENCES blog_comments(comment_id)
                            ON DELETE CASCADE,
    content             TEXT NOT NULL,
    like_count          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_comments_blog    ON blog_comments(blog_id, created_at)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_comments_parent  ON blog_comments(parent_comment_id)
    WHERE deleted_at IS NULL;

-- Blog comment likes
CREATE TABLE blog_comment_likes (
    blog_comment_id     UUID NOT NULL REFERENCES blog_comments(comment_id)
                            ON DELETE CASCADE,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id),
    liked_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(blog_comment_id, user_id)
);

-- Blog views (unique per user per day to prevent inflation)
CREATE TABLE blog_views (
    id                  BIGSERIAL PRIMARY KEY,
    blog_id             UUID NOT NULL REFERENCES blogs(blog_id)
                            ON DELETE CASCADE,
    viewer_user_id      VARCHAR(32) REFERENCES users(user_id),
    viewer_ip           INET,  -- for anonymous views
    viewed_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Only count once per user per day
    UNIQUE(blog_id, viewer_user_id)
);

CREATE INDEX idx_blog_views ON blog_views(blog_id);

-- Blog follows (follow an author)
CREATE TABLE blog_follows (
    follower_user_id    VARCHAR(32) NOT NULL REFERENCES users(user_id),
    following_user_id   VARCHAR(32) NOT NULL REFERENCES users(user_id),
    followed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(follower_user_id, following_user_id)
);

CREATE INDEX idx_blog_follows_following ON blog_follows(following_user_id);

-- Blog bookmarks (save for later)
CREATE TABLE blog_bookmarks (
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id),
    blog_id             UUID NOT NULL REFERENCES blogs(blog_id)
                            ON DELETE CASCADE,
    bookmarked_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(user_id, blog_id)
);

-- ============================================================================
-- CALLS SIGNALING
-- ============================================================================

CREATE TABLE call_participants (
    id                  BIGSERIAL PRIMARY KEY,
    call_id             UUID NOT NULL REFERENCES calls(call_id)
                            ON DELETE CASCADE,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id),
    -- State per participant: invited | joined | declined | left
    state               VARCHAR(20) NOT NULL DEFAULT 'invited',
    joined_at           TIMESTAMPTZ,
    left_at             TIMESTAMPTZ,
    UNIQUE(call_id, user_id)
);


-- ============================================================================
-- COLLABORATIVE NOTES
-- ============================================================================

CREATE TABLE collaborative_notes (
    id                  BIGSERIAL PRIMARY KEY,
    note_id             UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    creator_user_id     VARCHAR(32) NOT NULL REFERENCES users(user_id),
    conversation_jid    VARCHAR(255) NOT NULL,
    title               VARCHAR(100) NOT NULL,
    content             TEXT NOT NULL DEFAULT '',
    revision            INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_notes_conv ON collaborative_notes(conversation_jid)
    WHERE deleted_at IS NULL;

-- OT operation log for note conflict resolution
CREATE TABLE note_operations (
    id          BIGSERIAL PRIMARY KEY,
    note_id     UUID NOT NULL REFERENCES collaborative_notes(note_id)
                    ON DELETE CASCADE,
    revision    INT NOT NULL,
    op_type     VARCHAR(20) NOT NULL,  -- insert | delete | replace
    position    INT NOT NULL,
    text        TEXT,
    length      INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(note_id, revision)
);

-- Auto-delete old operations (keep last 1000 per note)
CREATE INDEX idx_note_ops ON note_operations(note_id, revision);

-- ============================================================================
-- BOTS / OPEN API
-- ============================================================================

CREATE TABLE bots (
    id                  BIGSERIAL PRIMARY KEY,
    bot_id              VARCHAR(20) NOT NULL UNIQUE,  -- bot_7f3a9b2c
    bot_name            VARCHAR(32) NOT NULL UNIQUE,  -- URL-safe name
    jid                 VARCHAR(255) NOT NULL UNIQUE,
    display_name        VARCHAR(100),
    description         VARCHAR(500),
    webhook_url         VARCHAR(500),
    webhook_secret_hash VARCHAR(64),  -- SHA-256 of webhook secret
    api_key_hash        VARCHAR(64) NOT NULL UNIQUE,
    api_secret_hash     VARCHAR(64) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT true,
    is_admin            BOOLEAN NOT NULL DEFAULT false,
    last_active         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bots_api_key ON bots(api_key_hash);
CREATE INDEX idx_bots_jid     ON bots(jid);

-- ============================================================================
-- VERIFIED ACCOUNTS
-- ============================================================================

CREATE TABLE verified_accounts (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(32) NOT NULL UNIQUE REFERENCES users(user_id)
                            ON DELETE CASCADE,
    verification_type   VARCHAR(20) NOT NULL, -- blue_check|gold_check|official
    verification_text   VARCHAR(200),         -- "Verified musician" etc.
    granted_by          VARCHAR(32) REFERENCES users(user_id),
    granted_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX idx_verified_user ON verified_accounts(user_id)
    WHERE revoked_at IS NULL;

-- ============================================================================
-- USER ROLES (for admin access control)
-- ============================================================================

CREATE TABLE user_roles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL REFERENCES users(user_id)
                    ON DELETE CASCADE,
    role        VARCHAR(30) NOT NULL,  -- admin | moderator | support
    granted_by  VARCHAR(32) REFERENCES users(user_id),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, role)
);

-- ============================================================================
-- CALL LOGS (already in main schema, adding index)
-- ============================================================================

CREATE INDEX idx_calls_state ON calls(state)
    WHERE state IN ('ringing', 'answered');

-- ============================================================================
-- TRANSLATION CACHE (optional - reduces API calls)
-- ============================================================================

CREATE TABLE translation_cache (
    id              BIGSERIAL PRIMARY KEY,
    content_hash    VARCHAR(64) NOT NULL,  -- SHA-256 of source text
    source_lang     VARCHAR(10) NOT NULL,
    target_lang     VARCHAR(10) NOT NULL,
    translated_text TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(content_hash, source_lang, target_lang)
);

CREATE INDEX idx_translation_cache ON translation_cache(content_hash,
    source_lang, target_lang);

-- Auto-expire translations older than 30 days
CREATE INDEX idx_translation_expires ON translation_cache(last_used_at);


-- ============================================================================
-- MULTI-DEVICE SESSIONS
-- Tracks which resources have carbons enabled
-- ============================================================================

ALTER TABLE active_sessions
    ADD COLUMN IF NOT EXISTS carbons_enabled    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS carbon_enabled_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS device_id          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS device_name        VARCHAR(100);

-- Track which messages have already been carboned to which resources
-- Prevents duplicate delivery if same resource connects twice
CREATE TABLE carbon_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    message_id      UUID NOT NULL,
    resource_uid    VARCHAR(64) NOT NULL, -- The session uid that received it
    delivered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(message_id, resource_uid)
);

CREATE INDEX idx_carbon_msg ON carbon_deliveries(message_id);

-- ============================================================================
-- DEVICE REGISTRY
-- Tracks all devices a user has ever logged in from
-- ============================================================================

CREATE TABLE user_devices (
    id              BIGSERIAL PRIMARY KEY,
    device_id       UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id         VARCHAR(32) NOT NULL REFERENCES users(user_id)
                        ON DELETE CASCADE,
    device_name     VARCHAR(100),           -- "Alice's iPhone 15"
    platform        VARCHAR(20) NOT NULL,   -- android | ios | web | desktop
    app_version     VARCHAR(20),
    push_token      VARCHAR(500),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active          BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_devices_user ON user_devices(user_id, active);

-- ============================================================================
-- DEVICE KEYS (for multi-device E2E encryption)
-- Each device has its own encryption keys
-- Signal Protocol: one key bundle per device
-- ============================================================================

CREATE TABLE device_keys (
    id                  BIGSERIAL PRIMARY KEY,
    device_id           UUID NOT NULL REFERENCES user_devices(device_id)
                            ON DELETE CASCADE,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id)
                            ON DELETE CASCADE,

    -- Long-term identity key for this device
    identity_key        TEXT NOT NULL,

    -- Signed pre-key (rotated periodically)
    signed_pre_key      TEXT NOT NULL,
    signed_pre_key_id   INT NOT NULL,
    signed_pre_key_sig  TEXT NOT NULL,  -- Signature proving key ownership

    -- Registration ID (random per device, used in Signal Protocol)
    registration_id     INT NOT NULL,

    key_version         INT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(device_id)
);

CREATE INDEX idx_device_keys_user ON device_keys(user_id);

-- One-time pre-keys per device
CREATE TABLE device_one_time_keys (
    id              BIGSERIAL PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES user_devices(device_id)
                        ON DELETE CASCADE,
    user_id         VARCHAR(32) NOT NULL REFERENCES users(user_id),
    key_id          INT NOT NULL,
    public_key      TEXT NOT NULL,
    claimed_at      TIMESTAMPTZ,
    UNIQUE(device_id, key_id)
);

CREATE INDEX idx_device_otk_unclaimed ON device_one_time_keys(device_id)
    WHERE claimed_at IS NULL;


CREATE TABLE pending_receipts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_uid VARCHAR(255) NOT NULL,
    to_uid VARCHAR(32) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    receipt_type ENUM('received', 'displayed', 'server_received') NOT NULL, -- received|displayed|server_received
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPT NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
    FOREIGN KEY (to_uid) REFERENCES users(user_id)
);

CREATE INDEX idx_pending_receipts_user ON pending_receipts(to_uid);