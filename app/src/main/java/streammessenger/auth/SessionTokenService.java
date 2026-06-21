package streammessenger.auth;


import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;

/**
 * Issues and validates session tokens.
 * <p>
 * Tokens NEVER expire on their own.
 * They are only invalidated by explicit revocation:
 *   - User logs out (this device)
 *   - User logs out all devices
 *   - Admin disables account
 */
public final class SessionTokenService {

    private static final Logger logger =
            Logger.getLogger(SessionTokenService.class.getName());

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DatabaseManager db;

    public SessionTokenService(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Issues a new session token.
     * Called once after successful Firebase verification on signup/login.
     */
    public IssuedToken issueToken(String userId,
                                   String deviceLabel,
                                   String pushToken,
                                   String platform,
                                   String appVersion,
                                   String ipAddress) {
        String rawToken  = generateRawToken();
        String tokenHash = DatabaseManager.hashToken(rawToken);

        // Store with no expiry (NULL expires_at)
        db.storeSessionToken(
                userId,
                tokenHash,
                deviceLabel,
                pushToken,
                platform,
                appVersion,
                null,       // expires_at = NULL = never expires
                ipAddress
        );

        logger.info("Session token issued: userId=" + userId + " platform=" + platform);
        return new IssuedToken(rawToken, userId);
    }

    /**
     * Validates a session token.
     * <p>
     * SIGN UP path:  never called (token just issued)
     * LOG IN path:   called with stored token from device
     * XMPP auth:     called on every XMPP connection
     * <p>
     * Returns null if:
     *   - Token not found in DB
     *   - Token has been revoked (user logged out)
     *   - User account is disabled
     */
    public ValidatedToken validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;

        String tokenHash = DatabaseManager.hashToken(rawToken.trim());
        DatabaseManager.SessionTokenRecord record =
                db.getSessionToken(tokenHash);

        if (record == null) {
            logger.fine("Token not found");
            return null;
        }

        // Revoked = logged out
        if (record.revokedAt() != null) {
            logger.warning("Revoked token used: userId=" + record.userId());
            return null;
        }

        // No expiry check - tokens live forever until revoked

        // Update last used timestamp
        db.touchSessionToken(tokenHash);

        return new ValidatedToken(record.userId(),
                record.pushToken(), record.platform());
    }

    // =========================================================================
    // Private
    // =========================================================================

    /**
     * Generates a cryptographically random session token.
     * <p>
     * Format: st_<32 hex chars>
     * Entropy: 128 bits (16 bytes)
     * <p>
     * At 1 billion tokens the collision probability is:
     * 1 - e^(-n²/2m) ≈ 1.47 × 10⁻¹⁰ (essentially zero)
     */
    private String generateRawToken() {
        byte[] bytes = new byte[16]; // 128 bits
        SECURE_RANDOM.nextBytes(bytes);

        StringBuilder sb = new StringBuilder("st_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        // Result: "st_" + 32 hex chars = 35 chars total
        // Example: "st_7f3a9b2c4d5e6f7a8b9c0d1e2f3a4b5c"
        return sb.toString();
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public record IssuedToken(
            String rawToken,  // st_7f3a9b2c... - sent to client ONCE
            String userId     // u_7f3a9b2c
    ) {}

    public record ValidatedToken(
            String userId, // u_7f3a9b2ce
            String pushToken,
            String platform
    ) {}
}