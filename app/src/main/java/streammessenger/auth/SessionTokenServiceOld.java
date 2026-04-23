package streammessenger.auth;


import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;

/**
 * Issues and validates session tokens.
 *
 * A session token is a cryptographically random string issued after
 * Firebase auth verification. It replaces passwords for XMPP auth.
 *
 * Token format: st_<32 random hex chars>
 * Example:      st_7f3a9b2c4d5e6f7a8b9c0d1e2f3a4b5c
 *
 * Storage:
 *   Raw token is sent to the client ONCE and never stored.
 *   We store SHA-256(token) in the database.
 *   On validation: hash the presented token and look up the hash.
 *   Same pattern as API keys in GitHub, Stripe, etc.
 *
 * Expiry:
 *   Tokens expire after 30 days by default.
 *   Expiry is extended on each successful XMPP login.
 *   Revoked explicitly on logout.
 */
public final class SessionTokenServiceOld {

    private static final Logger logger =
            Logger.getLogger(SessionTokenServiceOld.class.getName());

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Token lifetime: 30 days
    private static final long TOKEN_LIFETIME_DAYS = 30;

    // Extend by this much on each use
    private static final long EXTEND_ON_USE_DAYS = 7;

    private final DatabaseManager db;

    public SessionTokenServiceOld(DatabaseManager db) {
        this.db = db;
    }

    // =========================================================================
    // Token issuance
    // =========================================================================

    /**
     * Issues a new session token for a user.
     * Called after successful Firebase token verification.
     *
     * @return The issued token (raw value sent to client, stored as hash)
     */
    public IssuedToken issueToken(String userId,
                                   String deviceLabel,
                                   String pushToken,
                                   String platform,
                                   String appVersion,
                                   String ipAddress) {
        // Generate raw token
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = Instant.now().plus(
                TOKEN_LIFETIME_DAYS, ChronoUnit.DAYS);

        // Store hashed token
        db.storeSessionToken(
                userId,
                tokenHash,
                deviceLabel,
                pushToken,
                platform,
                appVersion,
                expiresAt,
                ipAddress
        );

        logger.info("Session token issued for userId=" + userId
                + " platform=" + platform);

        return new IssuedToken(rawToken, userId, expiresAt.toString());
    }

    // =========================================================================
    // Token validation
    // =========================================================================

    /**
     * Validates a session token.
     *
     * Called by:
     *   1. XMPP AuthManager (during SASL authentication)
     *   2. HTTP API endpoints (Bearer token validation)
     *
     * On success: extends the token expiry.
     *
     * @param rawToken The raw token presented by the client
     * @return ValidatedToken if valid, null if invalid/expired/revoked
     */
    public ValidatedToken validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;

        // Clean the token (remove "st_" prefix if client sent it with prefix)
        String cleanToken = rawToken.trim();

        String tokenHash = hashToken(cleanToken);

        DatabaseManager.SessionTokenRecord record =
                db.getSessionToken(tokenHash);

        if (record == null) {
            logger.fine("Token not found in database");
            return null;
        }

        if (record.revokedAt() != null) {
            logger.warning("Revoked token used by userId=" + record.userId());
            return null;
        }

        if (Instant.now().isAfter(record.expiresAt())) {
            logger.info("Expired token for userId=" + record.userId());
            db.revokeSessionToken(cleanToken, "expired");
            return null;
        }

        // Extend expiry on use (rolling session)
        Instant newExpiry = Instant.now().plus(
                EXTEND_ON_USE_DAYS, ChronoUnit.DAYS);

        // Only extend if the new expiry is further than current
        if (newExpiry.isAfter(record.expiresAt())) {
            db.extendSessionToken(tokenHash, newExpiry);
        }

        // Update last used
        db.touchSessionToken(tokenHash);

        return new ValidatedToken(record.userId(), record.pushToken(),
                record.platform());
    }

    // =========================================================================
    // Private
    // =========================================================================

    /**
     * Generates a cryptographically random session token.
     * Format: st_<32 hex chars>  = st_ + 128 bits of entropy
     */
    private String generateRawToken() {
        byte[] bytes = new byte[16]; // 128 bits
        SECURE_RANDOM.nextBytes(bytes);

        StringBuilder sb = new StringBuilder("st_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * SHA-256 hash of the raw token.
     * This is what we store in the database.
     */
    private String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                    rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public record IssuedToken(
            String rawToken,  // Sent to client ONCE, never stored
            String userId,
            String expiresAt
    ) {}

    public record ValidatedToken(
            String userId,
            String pushToken,
            String platform
    ) {}
}