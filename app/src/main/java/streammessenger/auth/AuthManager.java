package streammessenger.auth;


import java.util.Optional;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.exception.AuthenticationException;
import streammessenger.metrics.ServerMetrics;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.session.SessionState;


/**
 * Coordinates SASL authentication.
 *
 * Flow:
 *  1. Validate mechanism is supported
 *  2. Validate TLS is active (required before PLAIN)
 *  3. Decode SASL payload
 *  4. Verify credentials against database
 *  5. On success: bind session to contactId, update state
 *  6. On failure: restore state, increment metric
 *
 * Stateless singleton - safe to share across all connections.
 */
public final class AuthManager {

    private static final Logger logger = Logger.getLogger(AuthManager.class.getName());

    // Maximum failed auth attempts before temporary lockout
    private static final int MAX_FAILURES = 5;

    // Lockout duration in milliseconds (5 minutes)
    private static final long LOCKOUT_MS = 5 * 60 * 1000L;

    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final ServerMetrics metrics;

    // Track failed attempts per IP for rate limiting
    // IP → [failureCount, firstFailureTime]
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> failureTracker =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AuthManager(DatabaseManager db, SessionRegistry registry,
                       ServerMetrics metrics) {
        this.db = db;
        this.registry = registry;
        this.metrics = metrics;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Processes a SASL authentication attempt.
     *
     * @param mechanism "PLAIN" (only supported mechanism currently)
     * @param payload   Base64-encoded credentials from <auth> element
     * @param session   The session attempting authentication
     * @throws AuthenticationException on any auth failure
     */
    public void authenticate(String mechanism, String payload, Session session)
            throws AuthenticationException {

        // 1. Check mechanism [PLAIN is the only supported mechanism at the moment]
        if (!"PLAIN".equalsIgnoreCase(mechanism)) {
            metrics.authFailure();
            throw new AuthenticationException(
                    "Unsupported SASL mechanism: " + mechanism,
                    AuthenticationException.Reason.MECHANISM_NOT_SUPPORTED
            );
        }

        // 2. Require TLS - PLAIN over plain text is a security violation
        if (session.getSSLSocket() == null) {
            metrics.authFailure();
            throw new AuthenticationException(
                    "SASL PLAIN requires TLS negotiation first",
                    AuthenticationException.Reason.MECHANISM_NOT_SUPPORTED
            );
        }

        // 3. Check if this IP is locked out from too many failures
        String clientIp = session.getSocket().getInetAddress().getHostAddress();
        if (isLockedOut(clientIp)) {
            metrics.authFailure();
            throw new AuthenticationException(
                    "Too many failed attempts. Try again later.",
                    AuthenticationException.Reason.ACCOUNT_DISABLED
            );
        }

        session.setSessionState(SessionState.AUTHENTICATING);

        // 4. Decode SASL PLAIN payload
        //SASLMechanism.Credentials creds;
        Optional<String> uid;
        try {
            logger.info("Validating user firebase Auth token");
            uid = SASLMechanism.decodeFirebaseAuthToken(payload);
            //creds = SASLMechanism.decodePlain(payload);
        } catch (IllegalArgumentException e) {
            metrics.authFailure();
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED); //Fallback to the STARTTLS_NEGOTIATED State
            throw new AuthenticationException(
                    "Malformed SASL PLAIN payload: " + e.getMessage(),
                    AuthenticationException.Reason.MALFORMED_REQUEST
            );
        }

        // 5. Verify credentials

        //Optional<String> contactId = db.authenticateUser(
          //      creds.username(), creds.password());
        assert uid.isPresent();
        Optional<String> contactId = db.getUserContactId(uid.get());

        if (contactId.isEmpty()) {
            recordFailure(clientIp);
            metrics.authFailure();
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            throw new AuthenticationException(
                    "Invalid credentials for: " /*+ creds.username()*/,
                    AuthenticationException.Reason.INVALID_CREDENTIALS
            );
        }

        // 6. Auth succeeded
        clearFailures(clientIp);

        session.setContactId(contactId.get());
        session.setSessionState(SessionState.AUTHENTICATED);
        session.touchActivity();

        // Register in secondary index for message routing by contactId
        registry.bindAuthenticatedSession(contactId.get(), session);
        metrics.sessionAuthenticated();

        logger.info("Authenticated: " + contactId.get()
                + " uid=" + session.getUid()
                + " ip=" + clientIp);

        // Update last_seen asynchronously so it doesn't delay <success/>
        Thread.ofVirtual().name("last-seen-" + contactId.get())
                .start(() -> db.updateLastSeen(contactId.get()));
    }

    // =========================================================================
    // Rate limiting
    // =========================================================================

    private boolean isLockedOut(String ip) {
        long[] record = failureTracker.get(ip);
        if (record == null) return false;

        long failureCount = record[0];
        long firstFailureTime = record[1];

        // Reset if lockout period has expired
        if (System.currentTimeMillis() - firstFailureTime > LOCKOUT_MS) {
            failureTracker.remove(ip);
            return false;
        }

        return failureCount >= MAX_FAILURES;
    }

    private void recordFailure(String ip) {
        failureTracker.compute(ip, (key, existing) -> {
            if (existing == null) {
                return new long[]{1, System.currentTimeMillis()};
            }
            // Check if outside lockout window - reset if so
            if (System.currentTimeMillis() - existing[1] > LOCKOUT_MS) {
                return new long[]{1, System.currentTimeMillis()};
            }
            existing[0]++;
            return existing;
        });
    }

    private void clearFailures(String ip) {
        failureTracker.remove(ip);
    }

    /**
     * Periodic cleanup of expired failure records.
     * Call this from a scheduled task to prevent memory leak.
     */
    public void cleanExpiredFailureRecords() {
        long now = System.currentTimeMillis();
        failureTracker.entrySet().removeIf(
                entry -> now - entry.getValue()[1] > LOCKOUT_MS
        );
    }
}