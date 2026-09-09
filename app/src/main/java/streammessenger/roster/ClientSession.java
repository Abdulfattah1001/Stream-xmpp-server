package streammessenger.roster;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One connected device. Owns: bounded outbound queue, coalescing map of pending invalidations,
 * per-contact sent-version dedup, and slow-client state. The transport (socket layer) drains
 * pollOutbound() from its IO thread; nothing here blocks.
 */
public final class ClientSession {

    /** Integration point with the socket/stream layer. */
    public interface Transport {
        void wakeWriter(ClientSession session);                       // "there is something to write"
        void close(ClientSession session, String streamErrorCondition); // e.g. "resource-constraint"
    }

    private static final String FLUSH_MARKER = "\u0000flush";

    private final String sessionId, userId, deviceId;
    private final Transport transport;
    private final SyncMetrics metrics;
    private final ArrayBlockingQueue<String> outbound;
    private final ConcurrentHashMap<String, long[]> pending = new ConcurrentHashMap<>(); // contact → {version, seq}
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);
    private final SentVersionCache sent;
    private final int maxPending;
    private final AtomicLong lastAckSeq = new AtomicLong(-1);
    private volatile boolean resyncRequired = false;
    private volatile boolean open = true;
    private volatile boolean indexed = true;
    private volatile Set<String> indexedContacts = Set.of();

    public ClientSession(String sessionId, String userId, String deviceId, Transport transport, SyncConfig cfg, SyncMetrics metrics) {
        this.sessionId = Objects.requireNonNull(sessionId); this.userId = Objects.requireNonNull(userId);
        this.deviceId = Objects.requireNonNull(deviceId); this.transport = Objects.requireNonNull(transport);
        this.metrics = Objects.requireNonNull(metrics);
        this.outbound = new ArrayBlockingQueue<>(cfg.sessionQueueCapacity());
        this.sent = new SentVersionCache(cfg.sentVersionCacheSize());
        this.maxPending = cfg.maxPendingInvalidations();
    }

    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String deviceId() { return deviceId; }
    public boolean isOpen() { return open; }
    public boolean isIndexed() { return indexed; }
    Set<String> indexedContacts() { return indexedContacts; }
    void setIndexed(Set<String> contacts) { this.indexed = true; this.indexedContacts = contacts; }
    void setUnindexed() { this.indexed = false; this.indexedContacts = Set.of(); }
    public long lastAckSeq() { return lastAckSeq.get(); }
    public void recordAck(long seq) { lastAckSeq.accumulateAndGet(seq, Math::max); }

    /**
     * Hint that contact's profile is at `version`. Non-blocking. Coalesces with pending hints for the same
     * contact (max version wins). Never fails the caller; overflow degrades to a single "resync required".
     */
    public void invalidate(String contactId, long version, long seq) {
        if (!open) return;
        if (sent.covers(contactId, version)) { metrics.invalidationsDeduped.increment(); return; }
        pending.merge(contactId, new long[]{version, seq}, (a, b) -> a[0] >= b[0] ? a : b);
        metrics.invalidationsQueued.increment();
        if (pending.size() > maxPending) { pending.clear(); requireResync(); return; }
        if (flushQueued.compareAndSet(false, true)) {
            if (!outbound.offer(FLUSH_MARKER)) {           // queue full of other traffic: degrade, don't block
                flushQueued.set(false);
                pending.clear();
                requireResync();
                return;
            }
            transport.wakeWriter(this);
        }
    }

    /** Slow-client degradation: drop hints, tell the client once to reconcile from its cursor. */
    public void requireResync() {
        if (!resyncRequired) { resyncRequired = true; metrics.resyncRequired.increment(); transport.wakeWriter(this); }
    }

    /** Enqueue a response stanza (iq result etc.). Returns false if the client is too slow; caller decides policy. */
    public boolean send(String stanza) {
        if (!open) return false;
        boolean ok = outbound.offer(stanza);
        if (ok) transport.wakeWriter(this);
        return ok;
    }

    /** Called by the IO thread. Returns the next serialized stanza, or null when nothing is pending. */
    public String pollOutbound() {
        if (resyncRequired) { resyncRequired = false; return ProfileWire.syncRequired(); }
        while (true) {
            String s = outbound.poll();
            if (s == null) return null;
            if (s != FLUSH_MARKER) return s;              // identity check is intentional (sentinel instance)
            flushQueued.set(false);                        // BEFORE draining so a concurrent merge re-arms a flush
            String batch = buildInvalidationBatch();
            if (batch != null) return batch;               // else redundant marker: keep draining
        }
    }

    private String buildInvalidationBatch() {
        List<ProfileWire.Invalidation> items = new ArrayList<>();
        for (Iterator<Map.Entry<String, long[]>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, long[]> e = it.next();
            it.remove();
            long[] v = e.getValue();
            if (sent.covers(e.getKey(), v[0])) continue;
            sent.record(e.getKey(), v[0]);
            items.add(new ProfileWire.Invalidation(e.getKey(), v[0], v[1]));
        }
        if (items.isEmpty()) return null;
        metrics.invalidationsSent.add(items.size());
        metrics.invalidationStanzas.increment();
        return ProfileWire.invalidate(items);
    }

    /** Called by the transport when the stream ends (any reason). Idempotent. */
    public void close() {
        if (!open) return;
        open = false;
        outbound.clear();
        pending.clear();
    }

    @Override public String toString() { return "Session[" + userId + "/" + deviceId + "#" + sessionId + "]"; }
}