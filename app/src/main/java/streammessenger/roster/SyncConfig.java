package streammessenger.roster;

import java.time.Duration;

public record SyncConfig(
        String nodeId,
        long pollIntervalMs,            // worker idle poll period (cross-node latency bound)
        long maxBackoffMs,              // worker error backoff cap
        int batchSize,                  // change rows per poll
        long gapTimeoutMs,              // native-sequence gaps older than this are abandoned
        int maxTrackedSeqs,             // bound on gap tracker memory
        int sessionQueueCapacity,       // per-session outbound stanzas
        int maxPendingInvalidations,    // per-session coalescing map bound (≤ roster size)
        int sentVersionCacheSize,       // per-session dedup LRU
        int maxIndexedRoster,           // sessions above this use batched roster filtering instead of the interest index
        int maxReconcileInList,         // roster size above which reconcile switches to log scan
        int maxReconcileScan,           // log rows scanned before falling back to full reconcile
        long reconcileOverlap,          // seq overlap on reconnect (0 if commit-ordered sequencer)
        long checkpointFlushIntervalMs,
        int profileCacheSize,           // per-node hot-profile cache entries
        Duration changeLogRetention,
        int pruneBatch,
        int updateMaxRetries,
        boolean janitorEnabled) {

    public static SyncConfig defaults(String nodeId, boolean commitOrderedSequencer) {
        return new SyncConfig(nodeId, 300, 10_000, 500, 10_000, 100_000, 256, 5_000, 4_096, 20_000,
                5_000, 20_000, commitOrderedSequencer ? 0 : 1_000, 5_000, 50_000, Duration.ofDays(30), 5_000, 3, true);
    }
}