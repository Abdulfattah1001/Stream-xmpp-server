package streammessenger.config;


import java.util.logging.Logger;

import streammessenger.auth.SASLMechanism;
import streammessenger.auth.SessionTokenServiceOld;
import streammessenger.db.DatabaseManager;
import streammessenger.exception.AuthenticationException;
import streammessenger.metrics.ServerMetrics;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.session.SessionState;

/**
 * XMPP SASL authentication using session tokens.
 * <p>
 * Instead of username+password, the client sends:
 *   username = user_id (e.g. "u_7f3a9b2c")
 *   password = session_token (e.g. "st_7f3a9b2c...")
 * <p>
 * The session token was obtained from the HTTP /auth/login endpoint
 * after Firebase verification. It never expires until logout.
 * <p>
 * SASL PLAIN payload: \0user_id\0session_token
 */
public final class AuthManager {

    private static final Logger logger =
            Logger.getLogger(AuthManager.class.getName());

    private static final int MAX_FAILURES_PER_IP = 10;
    private static final long LOCKOUT_MS = 5 * 60 * 1000L;

    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final ServerMetrics metrics;
    private final SessionTokenServiceOld tokenService;

    // IP → [failCount, firstFailTime]
    private final java.util.concurrent.ConcurrentHashMap<String, long[]>
            failureTracker = new java.util.concurrent.ConcurrentHashMap<>();

    public AuthManager(DatabaseManager db,
                       SessionRegistry registry,
                       ServerMetrics metrics,
                       SessionTokenServiceOld tokenService) {
        this.db           = db;
        this.registry     = registry;
        this.metrics      = metrics;
        this.tokenService = tokenService;
    }

    /**
     * Authenticates an XMPP session using a session token.
     *
     * SASL PLAIN format: \0user_id\0session_token
     *   username = user_id    e.g. "u_7f3a9b2c"
     *   password = raw token  e.g. "st_abc123..."
     */
    public void authenticate(String mechanism, String payload, Session session)
            throws AuthenticationException {

        if (!"PLAIN".equalsIgnoreCase(mechanism)) {
            metrics.authFailure();
            throw new AuthenticationException(
                "Unsupported mechanism: " + mechanism,
                AuthenticationException.Reason.MECHANISM_NOT_SUPPORTED
            );
        }

        if (session.getSSLSocket() == null) {
            metrics.authFailure();
            throw new AuthenticationException(
                "TLS required before authentication",
                AuthenticationException.Reason.MECHANISM_NOT_SUPPORTED
            );
        }

        String clientIp = session.getSocket()
                .getInetAddress().getHostAddress();

        if (isLockedOut(clientIp)) {
            metrics.authFailure();
            throw new AuthenticationException(
                "Too many failed attempts",
                AuthenticationException.Reason.ACCOUNT_DISABLED
            );
        }

        session.setSessionState(SessionState.AUTHENTICATING);

        // Decode SASL PLAIN payload
        SASLMechanism.Credentials creds;
        try {
            creds = SASLMechanism.decodePlain(payload);
        } catch (IllegalArgumentException e) {
            recordFailure(clientIp);
            metrics.authFailure();
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            throw new AuthenticationException(
                "Malformed SASL payload",
                AuthenticationException.Reason.MALFORMED_REQUEST
            );
        }

        // creds.username() = user_id
        // creds.password() = session_token
        String userId      = creds.username();
        String sessionToken = creds.password();

        // Validate the session token
        SessionTokenServiceOld.ValidatedToken validated =
                tokenService.validate(sessionToken);

        if (validated == null) {
            recordFailure(clientIp);
            metrics.authFailure();
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            throw new AuthenticationException(
                "Invalid or expired session token",
                AuthenticationException.Reason.INVALID_CREDENTIALS
            );
        }

        // Ensure the token belongs to the claimed user_id
        if (!validated.userId().equals(userId)) {
            recordFailure(clientIp);
            metrics.authFailure();
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            throw new AuthenticationException(
                "Token does not match user_id",
                AuthenticationException.Reason.INVALID_CREDENTIALS
            );
        }

        // Load user record to get their JID
        DatabaseManager.UserRecord user = db.getUserByUserId(userId);

        if (user == null || !user.active()) {
            metrics.authFailure();
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            throw new AuthenticationException(
                "Account not found or disabled",
                AuthenticationException.Reason.ACCOUNT_DISABLED
            );
        }

        // Auth succeeded
        clearFailures(clientIp);

        session.setContactId(user.jid());
        session.setSessionState(SessionState.AUTHENTICATED);
        session.touchActivity();

        registry.bindAuthenticatedSession(user.jid(), session);
        metrics.sessionAuthenticated();

        logger.info("Authenticated: userId=" + userId
                + " jid=" + user.jid()
                + " uid=" + session.getUid()
                + " ip=" + clientIp);

        Thread.ofVirtual()
              .name("last-seen-" + userId)
              .start(() -> db.updateLastSeen(userId));
    }

    // =========================================================================
    // Rate limiting (same as before)
    // =========================================================================

    private boolean isLockedOut(String ip) {
        long[] record = failureTracker.get(ip);
        if (record == null) return false;
        if (System.currentTimeMillis() - record[1] > LOCKOUT_MS) {
            failureTracker.remove(ip);
            return false;
        }
        return record[0] >= MAX_FAILURES_PER_IP;
    }

    private void recordFailure(String ip) {
        failureTracker.compute(ip, (k, v) -> {
            if (v == null || System.currentTimeMillis() - v[1] > LOCKOUT_MS) {
                return new long[]{1, System.currentTimeMillis()};
            }
            v[0]++;
            return v;
        });
    }

    private void clearFailures(String ip) {
        failureTracker.remove(ip);
    }

    public void cleanExpiredFailureRecords() {
        long now = System.currentTimeMillis();
        failureTracker.entrySet()
                .removeIf(e -> now - e.getValue()[1] > LOCKOUT_MS);
    }
}