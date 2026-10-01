package streammessenger.roster;


import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.*;

/** Composition root for one application node. */
public final class ProfileSyncNode implements AutoCloseable {
    private final SyncConfig cfg;
    private final SyncMetrics metrics = new SyncMetrics();
    private final SessionRegistry registry;
    private final ProfileSyncWorker worker;
    private final ProfileSyncManager manager;
    private final ProfileUpdateService updates;
    private final ChangeLogJanitor janitor;
    private final ScheduledExecutorService scheduler;

    public ProfileSyncNode(DataSource ds, ChangeSequencer sequencer, SyncConfig cfg) {
        this.cfg = cfg;
        Clock clock = Clock.systemUTC();
        ProfileStore profiles = new JdbcProfileStore();
        ProfileChangeLog log = new JdbcProfileChangeLog(ds, sequencer);
        CheckpointStore checkpoints = new JdbcCheckpointStore(ds);
        RosterStore roster = new JdbcRosterStore(ds);
        this.registry = new SessionRegistry(cfg.maxIndexedRoster(), metrics);
        this.manager = new ProfileSyncManager(ds, profiles, log, checkpoints, roster, registry, cfg, metrics, clock);
        this.worker = new ProfileSyncWorker(log, registry, roster, cfg, metrics, manager::onChangeObserved);
        this.updates = new ProfileUpdateService(ds, profiles, log, clock, metrics, worker::hint, cfg.updateMaxRetries());
        this.janitor = new ChangeLogJanitor(log, cfg, clock);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "profile-sync-scheduler"); t.setDaemon(true); return t; });
    }

    public void start() throws SQLException {
        worker.start();
        scheduler.scheduleWithFixedDelay(manager::flushCheckpoints, cfg.checkpointFlushIntervalMs(), cfg.checkpointFlushIntervalMs(), TimeUnit.MILLISECONDS);
        if (cfg.janitorEnabled()) scheduler.scheduleWithFixedDelay(janitor, 1, 60, TimeUnit.MINUTES);
    }

    public SyncMetrics metrics() { return metrics; }
    public SessionRegistry registry() { return registry; }
    public ProfileSyncManager manager() { return manager; }
    public ProfileUpdateService updates() { return updates; }
    public ClientSession newSession(String sessionId, String userId, String deviceId, ClientSession.Transport t) {
        return new ClientSession(sessionId, userId, deviceId, t, cfg, metrics);
    }

    /** Graceful: stop dispatching, flush acks, let the transport layer close streams. */
    @Override public void close() {
        try { worker.stop(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        scheduler.shutdown();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        manager.close();
    }
}