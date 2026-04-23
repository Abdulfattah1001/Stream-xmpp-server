package streammessenger.mutlidevice;


import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.session.SessionRegistry;

/**
 * Manages device registration and multi-device key distribution.
 * <p>
 * WHAT THIS DOES:
 * ────────────────
 * 1. Tracks all devices a user has ever used
 * 2. Manages per-device encryption keys (for E2E multi-device)
 * 3. Handles device-specific push tokens
 * 4. Provides the device list for key distribution
 *
 * WHY PER-DEVICE KEYS:
 * ─────────────────────
 * With single-device E2E:
 *   Alice/phone sends encrypted message to Bob
 *   Bob can read it on phone
 *   Bob's laptop: CANNOT read it (different key)
 *
 * With multi-device E2E (Signal Protocol approach):
 *   Alice encrypts ONCE PER RECIPIENT DEVICE
 *   So for Bob with phone + laptop:
 *   Alice sends: encrypt(message, bob_phone_key) + encrypt(message, bob_laptop_key)
 *   Both devices can decrypt independently
 *
 * HOW KEY DISTRIBUTION WORKS:
 * ────────────────────────────
 * 1. Alice gets Bob's device list: [phone_device_id, laptop_device_id]
 * 2. Alice fetches a key bundle for each device
 * 3. Alice performs ECDH key exchange with each device independently
 * 4. Alice encrypts the message separately for each device
 * 5. All encrypted versions are sent in one stanza
 * 6. Server routes each version to the correct device
 * 7. Each device decrypts its own version
 *
 * DEVICE OPERATIONS:
 * ──────────────────
 *   register    → Register a new device (called on first login)
 *   list        → Get all devices for a user (for key distribution)
 *   get_keys    → Fetch key bundle for a specific device
 *   upload_keys → Upload/refresh keys for this device
 *   remove      → Remove/deactivate a device
 */
public final class DeviceManager {

    private static final Logger logger =
            Logger.getLogger(DeviceManager.class.getName());

    private static final String DEVICE_NS = "urn:xmpp:device:0";

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    public DeviceManager(ConnectionPool pool, SessionRegistry registry) {
        this.pool     = pool;
        this.registry = registry;
    }

    // =========================================================================
    // Records
    // =========================================================================

    public record DeviceRecord(
            String deviceId,
            String userId,
            String deviceName,
            String platform,
            String appVersion,
            String lastSeenAt,
            boolean active
    ) {}

    public record DeviceKeyBundle(
            String deviceId,
            int registrationId,
            String identityKey,
            String signedPreKey,
            int signedPreKeyId,
            String signedPreKeySig,
            String oneTimePreKey,    // null if none available
            int oneTimePreKeyId
    ) {}

    // =========================================================================
    // Device Registration
    // =========================================================================

    /**
     * Registers a device for a user.
     * Called during XMPP resource binding.
     *
     * If the device was previously registered (same device_id from client),
     * updates its last_seen_at and push token.
     *
     * @param userId      The user's ID
     * @param clientDeviceId Optional device ID from client (null = generate new)
     * @param deviceName  Human-readable device name
     * @param platform    android | ios | web | desktop
     * @param appVersion  App version string
     * @param pushToken   FCM/APNs push token
     * @return The device record
     */
    public DeviceRecord registerDevice(String userId,
                                        String clientDeviceId,
                                        String deviceName,
                                        String platform,
                                        String appVersion,
                                        String pushToken) {

        String sql = """
            INSERT INTO user_devices (
                device_id, user_id, device_name, platform,
                app_version, push_token, first_seen_at, last_seen_at, active
            ) VALUES (
                COALESCE(?::uuid, gen_random_uuid()),
                ?, ?, ?,
                ?, ?, NOW(), NOW(), true
            )
            ON CONFLICT (device_id) DO UPDATE SET
                device_name  = EXCLUDED.device_name,
                platform     = EXCLUDED.platform,
                app_version  = EXCLUDED.app_version,
                push_token   = EXCLUDED.push_token,
                last_seen_at = NOW(),
                active       = true
            RETURNING
                device_id::text, user_id, device_name,
                platform, app_version, last_seen_at::text, active
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, clientDeviceId);
            stmt.setString(2, userId);
            stmt.setString(3, deviceName);
            stmt.setString(4, platform);
            stmt.setString(5, appVersion);
            stmt.setString(6, pushToken);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                conn.commit();

                return new DeviceRecord(
                        rs.getString("device_id"),
                        rs.getString("user_id"),
                        rs.getString("device_name"),
                        rs.getString("platform"),
                        rs.getString("app_version"),
                        rs.getString("last_seen_at"),
                        rs.getBoolean("active")
                );
            }

        } catch (SQLException e) {
            logger.severe("registerDevice error: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Device List
    // =========================================================================

    /**
     * Returns all active devices for a user.
     * Used by message senders to know how many encrypted versions to prepare.
     */
    public List<DeviceRecord> getDevicesForUser(String userId) {
        String sql = """
            SELECT
                device_id::text,
                user_id,
                device_name,
                platform,
                app_version,
                last_seen_at::text,
                active
            FROM user_devices
            WHERE user_id = ? AND active = true
            ORDER BY last_seen_at DESC
            """;

        List<DeviceRecord> devices = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    devices.add(new DeviceRecord(
                            rs.getString("device_id"),
                            rs.getString("user_id"),
                            rs.getString("device_name"),
                            rs.getString("platform"),
                            rs.getString("app_version"),
                            rs.getString("last_seen_at"),
                            rs.getBoolean("active")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.severe("getDevicesForUser error: " + e.getMessage());
        }

        return devices;
    }

    // =========================================================================
    // Key Management
    // =========================================================================

    /**
     * Uploads encryption keys for a device.
     * Called after device registration and periodically to refresh keys.
     *
     * @param deviceId        The device's UUID
     * @param registrationId  Random ID from Signal Protocol (per device)
     * @param identityKey     Base64 EC public key (long-term)
     * @param signedPreKey    Base64 EC public key (medium-term)
     * @param signedPreKeyId  ID of the signed pre-key
     * @param signedPreKeySig Base64 signature of signed pre-key
     * @param oneTimePreKeys  List of one-time pre-keys (base64 EC keys)
     */
    public boolean uploadDeviceKeys(String deviceId,
                                     int registrationId,
                                     String identityKey,
                                     String signedPreKey,
                                     int signedPreKeyId,
                                     String signedPreKeySig,
                                     List<PreKeyEntry> oneTimePreKeys) {

        String upsertKeysSql = """
            INSERT INTO device_keys (
                device_id, user_id, identity_key, signed_pre_key,
                signed_pre_key_id, signed_pre_key_sig,
                registration_id, created_at, updated_at
            )
            SELECT ?::uuid, ud.user_id, ?, ?, ?, ?, ?, NOW(), NOW()
            FROM user_devices ud
            WHERE ud.device_id = ?::uuid
            ON CONFLICT (device_id) DO UPDATE SET
                identity_key      = EXCLUDED.identity_key,
                signed_pre_key    = EXCLUDED.signed_pre_key,
                signed_pre_key_id = EXCLUDED.signed_pre_key_id,
                signed_pre_key_sig = EXCLUDED.signed_pre_key_sig,
                key_version       = device_keys.key_version + 1,
                updated_at        = NOW()
            """;

        String insertOtkSql = """
            INSERT INTO device_one_time_keys (device_id, user_id, key_id, public_key)
            SELECT ?::uuid, ud.user_id, ?, ?
            FROM user_devices ud WHERE ud.device_id = ?::uuid
            ON CONFLICT (device_id, key_id) DO NOTHING
            """;

        try (Connection conn = pool.getConnection()) {

            try (PreparedStatement stmt = conn.prepareStatement(upsertKeysSql)) {
                stmt.setString(1, deviceId);
                stmt.setString(2, identityKey);
                stmt.setString(3, signedPreKey);
                stmt.setInt(4, signedPreKeyId);
                stmt.setString(5, signedPreKeySig);
                stmt.setInt(6, registrationId);
                stmt.setString(7, deviceId);
                stmt.executeUpdate();
            }

            if (oneTimePreKeys != null && !oneTimePreKeys.isEmpty()) {
                try (PreparedStatement stmt =
                             conn.prepareStatement(insertOtkSql)) {
                    for (PreKeyEntry key : oneTimePreKeys) {
                        stmt.setString(1, deviceId);
                        stmt.setInt(2, key.keyId());
                        stmt.setString(3, key.publicKey());
                        stmt.setString(4, deviceId);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            conn.commit();
            logger.info("Device keys uploaded: deviceId=" + deviceId
                    + " otks=" + (oneTimePreKeys != null
                    ? oneTimePreKeys.size() : 0));
            return true;

        } catch (SQLException e) {
            logger.severe("uploadDeviceKeys error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fetches a key bundle for a specific device.
     * Called by the sender before encrypting a message.
     *
     * Claims one one-time pre-key (deleted after use for forward secrecy).
     */
    public DeviceKeyBundle getDeviceKeyBundle(String deviceId,
                                               String requestingUserId) {
        String keysSql = """
            SELECT
                dk.registration_id,
                dk.identity_key,
                dk.signed_pre_key,
                dk.signed_pre_key_id,
                dk.signed_pre_key_sig
            FROM device_keys dk
            WHERE dk.device_id = ?::uuid
            """;

        String claimOtkSql = """
            UPDATE device_one_time_keys
            SET claimed_at = NOW()
            WHERE id = (
                SELECT id FROM device_one_time_keys
                WHERE device_id  = ?::uuid
                  AND claimed_at IS NULL
                ORDER BY key_id ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING key_id, public_key
            """;

        try (Connection conn = pool.getConnection()) {

            int    registrationId  = 0;
            String identityKey     = null;
            String signedPreKey    = null;
            int    signedPreKeyId  = 0;
            String signedPreKeySig = null;

            try (PreparedStatement stmt = conn.prepareStatement(keysSql)) {
                stmt.setString(1, deviceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) return null;
                    registrationId  = rs.getInt("registration_id");
                    identityKey     = rs.getString("identity_key");
                    signedPreKey    = rs.getString("signed_pre_key");
                    signedPreKeyId  = rs.getInt("signed_pre_key_id");
                    signedPreKeySig = rs.getString("signed_pre_key_sig");
                }
            }

            String oneTimePreKey   = null;
            int    oneTimePreKeyId = 0;

            try (PreparedStatement stmt = conn.prepareStatement(claimOtkSql)) {
                stmt.setString(1, deviceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        oneTimePreKeyId = rs.getInt("key_id");
                        oneTimePreKey   = rs.getString("public_key");
                    }
                }
            }

            conn.commit();

            // Warn if running low on one-time pre-keys
            int remaining = countRemainingOtks(deviceId);
            if (remaining < 5) {
                logger.warning("Low OTK supply: deviceId=" + deviceId
                        + " remaining=" + remaining);
                // TODO: notify device to upload more keys via push
            }

            return new DeviceKeyBundle(
                    deviceId, registrationId,
                    identityKey, signedPreKey, signedPreKeyId,
                    signedPreKeySig, oneTimePreKey, oneTimePreKeyId
            );

        } catch (SQLException e) {
            logger.severe("getDeviceKeyBundle error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches key bundles for ALL devices of a user.
     * Called before sending a multi-device encrypted message.
     */
    public List<DeviceKeyBundle> getAllDeviceKeyBundles(String targetUserId,
                                                         String requestingUserId) {
        List<DeviceRecord> devices = getDevicesForUser(targetUserId);
        List<DeviceKeyBundle> bundles = new ArrayList<>();

        for (DeviceRecord device : devices) {
            DeviceKeyBundle bundle = getDeviceKeyBundle(
                    device.deviceId(), requestingUserId);
            if (bundle != null) {
                bundles.add(bundle);
            }
        }

        return bundles;
    }

    // =========================================================================
    // Device removal
    // =========================================================================

    /**
     * Deactivates a device.
     * Called when user chooses "Remove device" in settings.
     */
    public boolean removeDevice(String deviceId, String userId) {
        String sql = """
            UPDATE user_devices
            SET active = false
            WHERE device_id::text = ? AND user_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, deviceId);
            stmt.setString(2, userId);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("removeDevice error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private int countRemainingOtks(String deviceId) {
        String sql = """
            SELECT COUNT(*) FROM device_one_time_keys
            WHERE device_id = ?::uuid AND claimed_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, deviceId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        } catch (SQLException e) {
            return 0;
        }
    }

    public record PreKeyEntry(int keyId, String publicKey) {}
}