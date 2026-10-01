package streammessenger.profile;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.metrics.ServerMetrics;
import streammessenger.sync.Jdbc;
import streammessenger.sync.SyncChangeLog;

public final class UpdateService {
    private final static Logger logger = Logger.getLogger(UpdateService.class.getName());
    public record UpdateResult(Profile profile, long changedSeq, boolean changed) {}
    private final ConnectionPool pool;
    private final ProfileStore store;
    private final SyncChangeLog changeLog;
    private final Clock clock;
    private final ServerMetrics metrics;
    private final LongConsumer afterCommit;
    private final int maxRetries;
    public UpdateService(ConnectionPool connectionPool, ProfileStore store, SyncChangeLog log, ServerMetrics metrics, LongConsumer afterCommit) {
        this.pool = connectionPool; this.store = store; this.changeLog = log; this.clock = Clock.systemUTC();
        this.metrics = metrics; this.afterCommit = afterCommit; this.maxRetries = 10;
    }

    public UpdateResult update(String userId, OptionalLong expectedVersion, ProfileMutation mutation) throws SQLException {
        logger.info("Updating user profile ... ");

        int attempt = 0;
        while(true) {
            try{
                UpdateResult r = Jdbc.inTransaction(pool, c -> apply(c, userId, expectedVersion, mutation));
                if(r.changed()) {
                    metrics.profileUpdates();
                    afterCommit.accept(r.changedSeq());
                }
                return r;
            }catch(SQLException e) {
                if(attempt ++ < maxRetries) continue;
                throw  e;
            }
        }
    }

    private UpdateResult apply(Connection c, String userId, OptionalLong expectedVersion, ProfileMutation mutation) throws  SQLException {
        Instant now = clock.instant();
        Profile current = store.lockForUpdate(c, userId).orElse(null);

        if (current == null) {
            // The user should never have passed authentication
            System.out.println("This user does not exists  on the server");
            throw new IllegalStateException("This user does not exist on the server");
        }

        Profile propose = mutation.apply(current);

        if (!propose.userId().equals(userId))
            throw new IllegalArgumentException("The userID does not matched with the owner of the profiles");

        int mask = current.diffMask(propose);

        // No update is needed as no field was changed
        if (mask == 0) return new UpdateResult(current, -1, false);

        Profile next = propose.withVersion(current.version() + 1, now);

        // Update the user profile

        if (!store.updateCase(c, next, current.version())) {
            throw new IllegalStateException("CAS failed while holing rows lock for: " + userId);
        }

        // Update the change log table
        long seq = changeLog.append(c, userId, next.version(), mask, now);

        return new UpdateResult(next, seq, true);
    }

    public Profile fetchProfile(String userId) throws  SQLException {
        return Jdbc.withConnection(pool, c -> store.find(c, userId).orElse(null));
    }
}
