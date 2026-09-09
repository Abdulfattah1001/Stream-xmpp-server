package streammessenger.roster;

import java.sql.SQLException;
import java.time.Clock;

/** Retention pruning in bounded batches. Idempotent and safe to run on several nodes (jittered); typically enable on one. */
public final class ChangeLogJanitor implements Runnable {
    private final ProfileChangeLog log; private final SyncConfig cfg; private final Clock clock;
    public ChangeLogJanitor(ProfileChangeLog log, SyncConfig cfg, Clock clock) { this.log = log; this.cfg = cfg; this.clock = clock; }
    @Override public void run() {
        try {
            int deleted;
            do { deleted = log.prune(clock.instant().minus(cfg.changeLogRetention()), cfg.pruneBatch()); }
            while (deleted >= cfg.pruneBatch());
        } catch (SQLException e) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "prune failed: {0}", e.toString());
        }
    }
}
