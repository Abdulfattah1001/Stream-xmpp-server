package streammessenger.roster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class SyncMetrics {
    public final LongAdder profileUpdates = new LongAdder(), profileUpdateNoops = new LongAdder(), profileUpdateConflicts = new LongAdder();
    public final LongAdder workerPolls = new LongAdder(), workerErrors = new LongAdder(), changesRead = new LongAdder(), changesCoalesced = new LongAdder();
    public final LongAdder invalidationsQueued = new LongAdder(), invalidationsSent = new LongAdder(), invalidationStanzas = new LongAdder(), invalidationsDeduped = new LongAdder();
    public final LongAdder resyncRequired = new LongAdder(), sessionsDropped = new LongAdder(), sessionsRegistered = new LongAdder();
    public final LongAdder profileGets = new LongAdder(), profileCacheHits = new LongAdder(), profileGetsForbidden = new LongAdder();
    public final LongAdder reconciles = new LongAdder(), fullReconciles = new LongAdder(), checkpointWrites = new LongAdder();
    public final AtomicLong lowWatermark = new AtomicLong(), maxSeqSeen = new AtomicLong(), lastPollEpochMs = new AtomicLong();
    public final AtomicLong openSessions = new AtomicLong(), interestKeys = new AtomicLong(), interestEntries = new AtomicLong();

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("profile_updates", profileUpdates.sum()); m.put("profile_update_noops", profileUpdateNoops.sum());
        m.put("profile_update_conflicts", profileUpdateConflicts.sum());
        m.put("worker_polls", workerPolls.sum()); m.put("worker_errors", workerErrors.sum());
        m.put("changes_read", changesRead.sum()); m.put("changes_coalesced", changesCoalesced.sum());
        m.put("invalidations_queued", invalidationsQueued.sum()); m.put("invalidations_sent", invalidationsSent.sum());
        m.put("invalidation_stanzas", invalidationStanzas.sum()); m.put("invalidations_deduped", invalidationsDeduped.sum());
        m.put("resync_required", resyncRequired.sum()); m.put("sessions_dropped", sessionsDropped.sum());
        m.put("profile_gets", profileGets.sum()); m.put("profile_cache_hits", profileCacheHits.sum()); m.put("profile_gets_forbidden", profileGetsForbidden.sum());
        m.put("reconciles", reconciles.sum()); m.put("full_reconciles", fullReconciles.sum()); m.put("checkpoint_writes", checkpointWrites.sum());
        m.put("low_watermark", lowWatermark.get()); m.put("max_seq_seen", maxSeqSeen.get()); m.put("lag_seqs", maxSeqSeen.get() - lowWatermark.get());
        m.put("open_sessions", openSessions.get()); m.put("interest_keys", interestKeys.get()); m.put("interest_entries", interestEntries.get());
        return m;
    }
}