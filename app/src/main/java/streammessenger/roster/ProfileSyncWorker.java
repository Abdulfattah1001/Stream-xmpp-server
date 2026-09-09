package streammessenger.roster;


import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reads the global change log (O(changed profiles)) and dispatches lightweight invalidations to local
 * interested sessions (O(local online interested)). Independent per node: no leases, no coordination.
 */
public final class ProfileSyncWorker implements Runnable {
    private final ProfileChangeLog log;
    private final SessionRegistry registry;
    private final RosterStore roster;
    private final SyncConfig cfg;
    private final SyncMetrics metrics;
    private final Consumer<ProfileChange> changeObserver;   // e.g. node profile cache eviction
    private final Semaphore wake = new Semaphore(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SequenceGapTracker tracker;
    private Thread thread;
    private long lastCursorPersistMs = 0;

    public ProfileSyncWorker(ProfileChangeLog log, SessionRegistry registry, RosterStore roster, SyncConfig cfg,
                             SyncMetrics metrics, Consumer<ProfileChange> changeObserver) {
        this.log = log; this.registry = registry; this.roster = roster; this.cfg = cfg; this.metrics = metrics;
        this.changeObserver = changeObserver;
    }

    /** Start at MAX(seq): a (re)started node has no sessions, so history is irrelevant (reconnects reconcile). */
    public synchronized void start() throws SQLException {
        if (!running.compareAndSet(false, true)) return;
        long start = log.maxSeq();
        tracker = new SequenceGapTracker(start, cfg.gapTimeoutMs(), cfg.maxTrackedSeqs());
        metrics.lowWatermark.set(start);
        metrics.maxSeqSeen.set(start);thread = new Thread(this, "profile-sync-worker[" + cfg.nodeId() + "]");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop(long timeoutMs) throws InterruptedException {
        if (!running.compareAndSet(true, false)) return;
        wake.release();
        thread.join(timeoutMs);
        if (thread.isAlive()) thread.interrupt();
    }

    /** Local wake-up after a commit on this node. Cross-node nodes rely on the poll interval (or NOTIFY/UDP hint). */
    public void hint(long seq) { if (wake.availablePermits() == 0) wake.release(); }

    public long lowWatermark() { SequenceGapTracker t = tracker; return t == null ? -1 : t.lowWatermark(); }

    @Override
    public void run() {
        long backoff = cfg.pollIntervalMs();
        while (running.get()) {
            try {
                boolean more = pollOnce();
                backoff = cfg.pollIntervalMs();
                if (!more) { wake.tryAcquire(cfg.pollIntervalMs(), TimeUnit.MILLISECONDS); wake.drainPermits(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (SQLException | RuntimeException e) {
                metrics.workerErrors.increment();
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "poll failed: {0}", e.toString());
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                backoff = Math.min(cfg.maxBackoffMs(), backoff * 2);
            }
        }
    }

    /** @return true if the batch was full (poll again immediately). */
    boolean pollOnce() throws SQLException {
        SequenceGapTracker t = tracker;
        long now = System.nanoTime();
        metrics.workerPolls.increment();
        List<ProfileChange> rows = log.readAfter(t.lowWatermark(), cfg.batchSize());
        metrics.lastPollEpochMs.set(System.currentTimeMillis());
        if (rows.isEmpty()) { t.expire(now); metrics.lowWatermark.set(t.lowWatermark()); return false; }

        // Coalesce within the batch: for a hot profile changed k times, dispatch only the highest version.
        LinkedHashMap<String, ProfileChange> latest = new LinkedHashMap<>();
        List<Long> seqs = new ArrayList<>(rows.size());
        for (ProfileChange r : rows) {
            if (t.isDone(r.seq())) continue;                    // re-read above a pending gap
            seqs.add(r.seq());
            ProfileChange prev = latest.put(r.userId(), r);
            if (prev != null) {
                metrics.changesCoalesced.increment();
                if (prev.version() > r.version()) latest.put(r.userId(), prev);   // defensive; seq order ≈ version order
            }
        }
        metrics.changesRead.add(seqs.size());
        t.observeMax(rows.get(rows.size() - 1).seq(), now);
        metrics.maxSeqSeen.accumulateAndGet(rows.get(rows.size() - 1).seq(), Math::max);

        dispatch(latest.values());

        for (long s : seqs) t.markDone(s);
        t.expire(now);
        metrics.lowWatermark.set(t.lowWatermark());
        persistCursorMaybe(t.lowWatermark());
        return rows.size() >= cfg.batchSize();
    }

    private void dispatch(Collection<ProfileChange> changes) throws SQLException {
        for (ProfileChange ch : changes) {
            changeObserver.accept(ch);                          // evict node cache BEFORE clients are told
            for (ClientSession s : registry.interestedIn(ch.userId())) s.invalidate(ch.userId(), ch.version(), ch.seq());
        }
        if (registry.hasUnindexed()) {
            Map<String, ProfileChange> byUser = new HashMap<>();
            for (ProfileChange ch : changes) byUser.put(ch.userId(), ch);
            for (ClientSession s : registry.unindexedSessions()) {
                if (!s.isOpen()) continue;
                Set<String> hits = roster.filterContacts(s.userId(), byUser.keySet());  // one chunked query per batch per huge-roster session
                for (String u : hits) { ProfileChange ch = byUser.get(u); s.invalidate(u, ch.version(), ch.seq()); }
                ProfileChange self = byUser.get(s.userId());
                if (self != null) s.invalidate(self.userId(), self.version(), self.seq());
            }
        }
    }

    private void persistCursorMaybe(long lw) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastCursorPersistMs < 5_000) return;
        lastCursorPersistMs = nowMs;
        try { log.recordNodeCursor(cfg.nodeId(), lw); } catch (SQLException e) { /* observability only */ }
    }
}
