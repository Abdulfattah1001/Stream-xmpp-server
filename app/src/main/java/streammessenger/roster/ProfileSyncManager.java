package streammessenger.roster;


import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ProfileSyncManager implements AutoCloseable {
    private final DataSource ds;
    private final ProfileStore profiles;
    private final ProfileChangeLog log;
    private final CheckpointStore checkpoints;
    private final RosterStore roster;
    private final SessionRegistry registry;
    private final SyncConfig cfg;
    private final SyncMetrics metrics;
    private final Clock clock;
    private final ConcurrentHashMap<ClientCheckpoint.Key, Long> dirtyAcks = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Profile> profileCache;     // per-node hot cache, coherent via the change log

    public ProfileSyncManager(DataSource ds, ProfileStore profiles, ProfileChangeLog log, CheckpointStore checkpoints,
                              RosterStore roster, SessionRegistry registry, SyncConfig cfg, SyncMetrics metrics, Clock clock) {
        this.ds = ds; this.profiles = profiles; this.log = log; this.checkpoints = checkpoints; this.roster = roster;
        this.registry = registry; this.cfg = cfg; this.metrics = metrics; this.clock = clock;
        this.profileCache = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Profile> e) { return size() > cfg.profileCacheSize(); }
        };
    }


    /**
     * Order matters: register FIRST so that any change committed from now on reaches the session live;
     * THEN compute the reconcile answer. Overlap between the two paths is harmless (idempotent versions).
     */
    public void onSessionOpen(ClientSession s, OptionalLong clientCursor, String iqId) throws SQLException {
        Set<String> contacts = roster.contactsOf(s.userId());
        registry.register(s, contacts);
        s.send(reconcile(s, iqId, contacts, clientCursor));
    }

    public void onSessionClosed(ClientSession s) {
        s.close();
        registry.unregister(s);
        flushOne(new ClientCheckpoint.Key(s.userId(), s.deviceId()));
    }

    // ------------------------------------------------------------ inbound requests

    /** Returns the response stanza to send, or null for fire-and-forget requests. */
    public String handle(ClientSession s, ProfileWire.Request req) throws SQLException {
        return switch (req) {
            case ProfileWire.GetRequest g       -> handleGet(s, g);
            case ProfileWire.AckRequest a       -> { handleAck(s, a.seq()); yield null; }
            case ProfileWire.SyncRequest r      -> reconcile(s, r.id(), rosterWithSelf(s), r.since());
            case ProfileWire.FullSyncRequest fr -> handleFullSync(s, fr);
        };
    }

    private String handleGet(ClientSession s, ProfileWire.GetRequest g) throws SQLException {
        metrics.profileGets.add(g.items().size());
        List<ProfileWire.ProfileResult> results = new ArrayList<>(g.items().size());
        Map<String, Long> unchangedVersions = new HashMap<>();
        for (ProfileWire.GetItem it : g.items()) {
            if (!roster.canView(s.userId(), it.userId())) { metrics.profileGetsForbidden.increment(); results.add(ProfileWire.ProfileResult.error(it.userId(), "forbidden")); continue; }
            Profile p = fetchProfile(it.userId(), it.have());
            if (p == null) { results.add(ProfileWire.ProfileResult.error(it.userId(), "item-not-found")); continue; }
            if (p.version() <= it.have()) { unchangedVersions.put(p.userId(), p.version()); results.add(ProfileWire.ProfileResult.unchanged(p.userId(), p.version())); }
            else results.add(ProfileWire.ProfileResult.full(p));
        }
        return ProfileWire.profiles(g.id(), results, unchangedVersions);
    }

    /**
     * Cache rule: serve from cache only if cached.version > have. If the client already has the cached version
     * it is asking because it learned of a newer one (reconcile/invalidation) and our cache may lag the poll
     * interval → go to the DB. Eviction happens from the worker before invalidations are dispatched.
     */
    private Profile fetchProfile(String userId, long have) throws SQLException {
        Profile cached;
        synchronized (profileCache) { cached = profileCache.get(userId); }
        if (cached != null && cached.version() > have) { metrics.profileCacheHits.increment(); return cached; }
        Profile fresh = Jdbc.withConnection(ds, c -> profiles.find(c, userId)).orElse(null);
        if (fresh != null) synchronized (profileCache) {
            Profile cur = profileCache.get(userId);
            if (cur == null || cur.version() < fresh.version()) profileCache.put(userId, fresh);
        }
        return fresh;
    }

    /** Worker callback: keep the node cache coherent with the log. */
    void onChangeObserved(ProfileChange ch) {
        synchronized (profileCache) {
            Profile cur = profileCache.get(ch.userId());
            if (cur != null && cur.version() < ch.version()) profileCache.remove(ch.userId());
        }
    }

    // ------------------------------------------------------------ reconciliation

    private Set<String> rosterWithSelf(ClientSession s) throws SQLException {
        Set<String> c = new HashSet<>(roster.contactsOf(s.userId())); c.add(s.userId()); return c;
    }

    private String reconcile(ClientSession s, String iqId, Set<String> contactsIn, OptionalLong clientCursor) throws SQLException {
        metrics.reconciles.increment();
        Set<String> contacts = new HashSet<>(contactsIn); contacts.add(s.userId());
        long upto = log.maxSeq();                                    // captured BEFORE the query (see §7 race analysis)
        long since;
        if (clientCursor.isPresent()) since = clientCursor.getAsLong();
        else since = checkpoints.load(s.userId(), s.deviceId()).map(ClientCheckpoint::lastChangeSeq).orElse(-1L);
        if (since < 0) return full(iqId, upto);
        since = Math.max(0, since - cfg.reconcileOverlap());
        long oldest = log.minSeq();
        if (oldest == 0 && upto > 0) return full(iqId, upto);        // log pruned to empty
        if (oldest > since + 1) return full(iqId, upto);             // beyond retention: cannot prove completeness

        List<ProfileVersion> changed;
        if (contacts.size() <= cfg.maxReconcileInList()) {
            changed = log.latestSince(since, contacts);
        } else {
            changed = scanSince(since, contacts);
            if (changed == null) return full(iqId, upto);
        }
        return ProfileWire.syncResult(iqId, since, upto, changed);
    }

    /** Huge-roster recipients: stream the log and filter in memory, bounded by maxReconcileScan. */
    private List<ProfileVersion> scanSince(long since, Set<String> contacts) throws SQLException {
        Map<String, Long> latest = new HashMap<>();
        long cursor = since; int scanned = 0;
        while (true) {
            List<ProfileChange> rows = log.readAfter(cursor, cfg.batchSize());
            if (rows.isEmpty()) break;
            for (ProfileChange r : rows) if (contacts.contains(r.userId())) latest.merge(r.userId(), r.version(), Math::max);
            scanned += rows.size(); cursor = rows.get(rows.size() - 1).seq();
            if (scanned >= cfg.maxReconcileScan()) return null;
        }
        List<ProfileVersion> out = new ArrayList<>(latest.size());
        latest.forEach((u, v) -> out.add(new ProfileVersion(u, v)));
        return out;
    }

    private String full(String iqId, long upto) { metrics.fullReconciles.increment(); return ProfileWire.syncFullRequired(iqId, upto); }

    /** Version-only compare, O(roster) longs, no payloads. Client sends its <have> list (paged for huge rosters). */
    private String handleFullSync(ClientSession s, ProfileWire.FullSyncRequest fr) throws SQLException {
        long upto = log.maxSeq();
        Set<String> contacts = rosterWithSelf(s);
        Set<String> scope = fr.have().isEmpty() ? contacts : new HashSet<>(fr.have().keySet());
        scope.retainAll(contacts);                                   // never leak versions of non-contacts
        Map<String, Long> current = Jdbc.withConnection(ds, c -> profiles.currentVersions(c, scope));
        List<ProfileVersion> changed = new ArrayList<>();
        for (var e : current.entrySet()) {
            long have = fr.have().getOrDefault(e.getKey(), -1L);
            if (e.getValue() > have) changed.add(new ProfileVersion(e.getKey(), e.getValue()));
        }
        return ProfileWire.syncResult(fr.id(), -1, upto, changed);
    }

    // ------------------------------------------------------------ checkpoints (write-behind)

    private void handleAck(ClientSession s, long seq) {
        if (seq < 0) return;
        s.recordAck(seq);
        dirtyAcks.merge(new ClientCheckpoint.Key(s.userId(), s.deviceId()), seq, Math::max);
    }

    /** Scheduled every checkpointFlushIntervalMs; also on session close and shutdown. */
    public void flushCheckpoints() {
        for (ClientCheckpoint.Key k : new ArrayList<>(dirtyAcks.keySet())) flushOne(k);
    }

    private void flushOne(ClientCheckpoint.Key k) {
        Long seq = dirtyAcks.remove(k);
        if (seq == null) return;
        try {
            checkpoints.save(new ClientCheckpoint(k.recipientUserId(), k.deviceId(), seq, clock.instant()));
            metrics.checkpointWrites.increment();
        } catch (SQLException e) {
            dirtyAcks.merge(k, seq, Math::max);                     // retry next flush; a lost checkpoint only costs reconcile work
        }
    }

    @Override public void close() { flushCheckpoints(); }
}
