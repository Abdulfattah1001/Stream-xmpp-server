package streammessenger.util;


// Without this, one bad client can DoS your server

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimiter {

    // Per-IP connection rate limiting
    private final ConcurrentHashMap<String, TokenBucket> connectionBuckets
        = new ConcurrentHashMap<>();

    // Per-session stanza rate limiting  
    private final ConcurrentHashMap<String, TokenBucket> stanzaBuckets
        = new ConcurrentHashMap<>();

    public boolean allowConnection(String ipAddress) {
        TokenBucket bucket = connectionBuckets.computeIfAbsent(
            ipAddress, k -> new TokenBucket(10, 1) // 10 connections, refill 1/sec
        );
        return bucket.tryConsume();
    }

    public boolean allowStanza(String sessionUid) {
        TokenBucket bucket = stanzaBuckets.computeIfAbsent(
            sessionUid, k -> new TokenBucket(100, 50) // 100 burst, 50/sec steady
        );
        return bucket.tryConsume();
    }

    private static final class TokenBucket {
        private final long capacity;
        private final long refillPerSecond;
        private final AtomicLong tokens;
        private volatile long lastRefill = System.nanoTime();

        TokenBucket(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = new AtomicLong(capacity);
        }

        boolean tryConsume() {
            refill();
            long current;
            do {
                current = tokens.get();
                if (current <= 0) return false;
            } while (!tokens.compareAndSet(current, current - 1));
            return true;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefill;
            long toAdd = (elapsed * refillPerSecond) / 1_000_000_000L;
            if (toAdd > 0) {
                tokens.updateAndGet(t -> Math.min(capacity, t + toAdd));
                lastRefill = now;
            }
        }
    }
}