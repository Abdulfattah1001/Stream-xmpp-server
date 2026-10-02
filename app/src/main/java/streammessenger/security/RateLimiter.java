package streammessenger.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Token bucket rate limiter - no external dependencies.
 * <p>
 * Limits:
 *   Per IP:      Max connections per minute
 *   Per Session: Max stanzas per second (prevents message flooding)
 *   Per Account: Max messages per day (prevents spam)
 *   Global:      Max new connections per second
 * <p>
 * Token Bucket Algorithm:
 *   Each bucket has N tokens.
 *   Each request consumes 1 token.
 *   Tokens refill at a fixed rate.
 *   When empty: requests are rejected.
 * <p>
 * Why token bucket vs leaky bucket:
 *   - Allows bursting (sending 10 messages quickly then pausing)
 *   - More natural for chat apps
 *   - Leaky bucket = fixed rate (better for streaming)
 */
public final class RateLimiter {

    private static final Logger logger =
            Logger.getLogger(RateLimiter.class.getName());

    // =========================================================================
    // Limits configuration
    // =========================================================================

    // Per IP: max 10 connection attempts per minute
    private static final int   IP_CONNECTION_CAPACITY     = 10;
    private static final long  IP_CONNECTION_REFILL_MS    = 6_000; // 1 token/6s

    // Per session: max 30 stanzas per 10 seconds (burst), 3/s steady
    private static final int   SESSION_STANZA_CAPACITY    = 30;
    private static final long  SESSION_STANZA_REFILL_MS   = 333; // 3 tokens/s

    // Per account: max 1000 messages per day
    private static final int   ACCOUNT_MESSAGE_CAPACITY   = 1000;
    private static final long  ACCOUNT_MESSAGE_REFILL_MS  = 86_400_000L / 1000; // 1 per 86.4s

    // Global: max 100 new connections per second
    private static final int   GLOBAL_CONNECT_CAPACITY    = 100;
    private static final long  GLOBAL_CONNECT_REFILL_MS   = 10; // 100/s

    // =========================================================================
    // Buckets
    // =========================================================================

    // IP address → connection bucket
    private final ConcurrentHashMap<String, TokenBucket> ipBuckets =
            new ConcurrentHashMap<>();

    // Session UID → stanza bucket
    private final ConcurrentHashMap<String, TokenBucket> sessionBuckets =
            new ConcurrentHashMap<>();

    // User ID → daily message bucket
    private final ConcurrentHashMap<String, TokenBucket> accountBuckets =
            new ConcurrentHashMap<>();

    // Global connection bucket (shared across all IPs)
    private final TokenBucket globalBucket = new TokenBucket(
            GLOBAL_CONNECT_CAPACITY, GLOBAL_CONNECT_REFILL_MS);

    // Lockout tracking: IP → lockout expiry timestamp
    private final ConcurrentHashMap<String, Long> lockedOutIPs =
            new ConcurrentHashMap<>();

    private static final RateLimiter INSTANCE = new RateLimiter();

    private RateLimiter() {
        // Periodically clean up old buckets
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleaner");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(
                this::cleanStaleBuckets,
                5, 5, java.util.concurrent.TimeUnit.MINUTES
        );
    }

    public static RateLimiter getInstance() {
        return INSTANCE;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Checks if a new TCP connection from this IP is allowed.
     *
     * @param ipAddress Client IP address
     * @return RateResult.ALLOWED or RateResult.REJECTED with reason
     */
    public RateResult allowConnection(String ipAddress) {
        // Check lockout first (fast path)
        Long lockoutExpiry = lockedOutIPs.get(ipAddress);
        if (lockoutExpiry != null) {
            if (System.currentTimeMillis() < lockoutExpiry) {
                return RateResult.rejected("IP temporarily locked out");
            }
            lockedOutIPs.remove(ipAddress);
        }

        // Global limit check
        if (!globalBucket.tryConsume()) {
            return RateResult.rejected("Server connection limit reached");
        }

        // Per-IP limit check
        TokenBucket ipBucket = ipBuckets.computeIfAbsent(ipAddress,
                k -> new TokenBucket(
                        IP_CONNECTION_CAPACITY,
                        IP_CONNECTION_REFILL_MS));

        if (!ipBucket.tryConsume()) {
            // Too many connections from this IP - lockout for 5 minutes
            lockedOutIPs.put(ipAddress,
                    System.currentTimeMillis() + 5 * 60_000L);
            logger.warning("IP rate limited and locked out: " + ipAddress);
            return RateResult.rejected(
                "Too many connections from your IP. Try again in 5 minutes.");
        }

        return RateResult.ALLOWED;
    }

    /**
     * Checks if a stanza from this session is allowed.
     *
     * @param sessionUid The session UID
     * @return true if allowed, false if rate limited
     */
    public boolean allowStanza(String sessionUid) {
        TokenBucket bucket = sessionBuckets.computeIfAbsent(sessionUid,
                k -> new TokenBucket(
                        SESSION_STANZA_CAPACITY,
                        SESSION_STANZA_REFILL_MS));

        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            logger.warning("Stanza rate limited: uid=" + sessionUid);
        }
        return allowed;
    }

    /**
     * Checks if a message from this account is allowed (daily limit).
     *
     * @param userId The sender's user_id
     * @return true if allowed, false if daily limit reached
     */
    public boolean allowMessage(String userId) {
        TokenBucket bucket = accountBuckets.computeIfAbsent(userId,
                k -> new TokenBucket(
                        ACCOUNT_MESSAGE_CAPACITY,
                        ACCOUNT_MESSAGE_REFILL_MS));

        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            logger.warning("Daily message limit reached: userId=" + userId);
        }
        return allowed;
    }

    /**
     * Removes rate limiting state for a session on disconnect.
     * Called by ConnectionHandler.cleanup().
     */
    public void onSessionDisconnected(String sessionUid) {
        sessionBuckets.remove(sessionUid);
    }

    /**
     * Returns current bucket state for monitoring.
     */
    public BucketStats getStats(String ipAddress, String sessionUid) {
        TokenBucket ip      = ipBuckets.get(ipAddress);
        TokenBucket session = sessionBuckets.get(sessionUid);

        return new BucketStats(
                ip      != null ? ip.getTokens()      : IP_CONNECTION_CAPACITY,
                session != null ? session.getTokens()  : SESSION_STANZA_CAPACITY,
                globalBucket.getTokens(),
                lockedOutIPs.containsKey(ipAddress)
        );
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    private void cleanStaleBuckets() {
        long now = System.currentTimeMillis();

        // Remove IP buckets that are full (haven't been used recently)
        ipBuckets.entrySet().removeIf(e ->
                e.getValue().isFullAndStale(now));

        // Remove expired lockouts
        lockedOutIPs.entrySet().removeIf(e -> now > e.getValue());

        // Account buckets reset daily - remove if full
        accountBuckets.entrySet().removeIf(e ->
                e.getValue().isFullAndStale(now));

        logger.fine("Rate limiter cleanup: ipBuckets=" + ipBuckets.size()
                + " sessionBuckets=" + sessionBuckets.size()
                + " lockedOut=" + lockedOutIPs.size());
    }

    // =========================================================================
    // Token Bucket
    // =========================================================================

    /**
     * Thread-safe token bucket implementation.
     *
     * Tokens refill continuously based on elapsed time.
     * Uses compare-and-set for lock-free operation under contention.
     */
    static final class TokenBucket {

        private final int capacity;
        private final long refillIntervalMs; // ms per token

        // Current token count as a fixed-point integer (tokens * 1000)
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;
        private final AtomicLong lastConsumeTime;

        TokenBucket(int capacity, long refillIntervalMs) {
            this.capacity          = capacity;
            this.refillIntervalMs  = refillIntervalMs;
            this.tokens            = new AtomicLong(capacity * 1000L);
            this.lastRefillTime    = new AtomicLong(System.currentTimeMillis());
            this.lastConsumeTime   = new AtomicLong(System.currentTimeMillis());
        }

        /**
         * Attempts to consume one token.
         * @return true if a token was available, false if bucket is empty
         */
        boolean tryConsume() {
            refill();
            lastConsumeTime.set(System.currentTimeMillis());

            long current;
            long updated;
            do {
                current = tokens.get();
                if (current < 1000) return false; // Less than 1 full token
                updated = current - 1000;
            } while (!tokens.compareAndSet(current, updated));

            return true;
        }

        private void refill() {
            long now  = System.currentTimeMillis();
            long last = lastRefillTime.get();
            long elapsed = now - last;

            if (elapsed <= 0) return;

            // How many tokens to add?
            long tokensToAdd = (elapsed * 1000L) / refillIntervalMs;
            if (tokensToAdd <= 0) return;

            if (!lastRefillTime.compareAndSet(last, now)) {
                return; // Another thread already refilled
            }

            long maxTokens = (long) capacity * 1000;
            tokens.updateAndGet(t -> Math.min(maxTokens, t + tokensToAdd));
        }

        int getTokens() {
            refill();
            return (int) (tokens.get() / 1000);
        }

        boolean isFullAndStale(long now) {
            return tokens.get() >= (long) capacity * 1000
                    && (now - lastConsumeTime.get()) > 10 * 60_000L; // 10 min
        }
    }

    // =========================================================================
    // Result types
    // =========================================================================

    public record RateResult(boolean allowed, String reason) {
        static final RateResult ALLOWED = new RateResult(true, null);

        static RateResult rejected(String reason) {
            return new RateResult(false, reason);
        }
    }

    public record BucketStats(
            int ipTokens,
            int sessionTokens,
            int globalTokens,
            boolean isLockedOut
    ) {}
}