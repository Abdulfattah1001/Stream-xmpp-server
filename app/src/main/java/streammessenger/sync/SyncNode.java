package streammessenger.sync;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import streammessenger.config.ServerConfig;
import streammessenger.db.ConnectionPool;
import streammessenger.metrics.ServerMetrics;
import streammessenger.profile.ProfileStore;
import streammessenger.profile.UpdateService;
import streammessenger.roster.RosterManager;
import streammessenger.session.SessionRegistry;

public class SyncNode implements AutoCloseable {
    private final ServerConfig cfg;
    private final ServerMetrics metrics;
    private final SessionRegistry registry;
    private final SyncWorker worker;
    private final UpdateService updateService;
    private final SyncChangeLog changeLog;
    private final ProfileStore profileStore;
    private final ScheduledExecutorService scheduler;
    private  SyncManager syncManager;

    public  SyncNode(ConnectionPool connectionPool, ServerConfig config, ServerMetrics metrics, SessionRegistry registry,
                    SyncChangeLog log, ProfileStore store){
        this.cfg = config; this.metrics = metrics; this.registry = registry;
        this.profileStore = store;
        this.changeLog = log;
        this.worker = new SyncWorker(registry, metrics, log, c -> {});
        this.updateService = new UpdateService(connectionPool, profileStore, changeLog, metrics, worker::hint);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {Thread t = new Thread("sync-scheduler"); t.setDaemon(true); t.start(); return t; });
    }

    public UpdateService getProfileUpdateService() {
        return updateService;
    }

    public void start() throws SQLException {
        worker.start();
        //scheduler.scheduleWithFixedDelay()
    }

    /** Graceful: stop dispatching, flush acks, let the transport layer close streams */
    @Override
    public void close() throws Exception {
        try{ worker.stop(5_000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler.shutdown();
        try{scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
