package streammessenger.roster;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

/**
 * The single durable state change: lock → CAS version bump → append change → commit.
 * Emits exactly one change row per effective update, regardless of roster size (O(1) writes).
 */
public final class ProfileUpdateService {
    public record UpdateResult(Profile profile, long changeSeq, boolean changed) {}

    private final DataSource ds;
    private final ProfileStore store;
    private final ProfileChangeLog changeLog;
    private final Clock clock;
    private final SyncMetrics metrics;
    private final LongConsumer afterCommit;     // e.g. worker::hint — never carries data, only wakes the local poller
    private final int maxRetries;

    public ProfileUpdateService(DataSource ds, ProfileStore store, ProfileChangeLog changeLog, Clock clock,
                                SyncMetrics metrics, LongConsumer afterCommit, int maxRetries) {
        this.ds = Objects.requireNonNull(ds); this.store = Objects.requireNonNull(store);
        this.changeLog = Objects.requireNonNull(changeLog); this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics); this.afterCommit = Objects.requireNonNull(afterCommit);
        this.maxRetries = maxRetries;
    }

    /**
     * @param expectedVersion optimistic guard from the client (its cached version). Empty = last-writer-wins.
     * @throws ProfileConflictException if expectedVersion is present and stale (client must refetch and retry).
     */
    public UpdateResult update(String userId, OptionalLong expectedVersion, ProfileMutation mutation) throws SQLException {
        int attempt = 0;
        while (true) {
            try {
                UpdateResult r = Jdbc.inTransaction(ds, c -> apply(c, userId, expectedVersion, mutation));
                if (r.changed()) { metrics.profileUpdates.increment(); afterCommit.accept(r.changeSeq()); }
                else metrics.profileUpdateNoops.increment();
                return r;
            } catch (ProfileConflictException e) {
                metrics.profileUpdateConflicts.increment();
                throw e;
            } catch (SQLException e) {
                // duplicate key: concurrent first-time insert race; class 40/08: deadlock/serialization/connection.
                if (attempt++ < maxRetries && (Jdbc.isDuplicateKey(e) || Jdbc.isTransient(e))) { backoff(attempt); continue; }
                throw e;
            }
        }
    }

    private UpdateResult apply(Connection c, String userId, OptionalLong expectedVersion, ProfileMutation mutation) throws SQLException {
        Instant now = clock.instant();
        Profile current = store.lockForUpdate(c, userId).orElse(null);
        if (current == null) {                       // first write: insert then continue holding the (new) row lock
            current = Profile.initial(userId, now);
            store.insert(c, current);
        }
        if (expectedVersion.isPresent() && expectedVersion.getAsLong() != current.version()) {
            throw new ProfileConflictException(userId, expectedVersion.getAsLong(), current.version());
        }
        Profile proposed = mutation.apply(current);
        if (!proposed.userId().equals(userId)) throw new IllegalStateException("mutation changed userId");
        int mask = current.diffMask(proposed);
        if (mask == 0) return new UpdateResult(current, -1, false);   // idempotent: identical retry → no version, no change row

        Profile next = proposed.withVersion(current.version() + 1, now);
        if (!store.updateCas(c, next, current.version())) {
            throw new IllegalStateException("CAS failed while holding row lock for " + userId);   // would indicate a broken isolation setup
        }
        long seq = changeLog.append(c, userId, next.version(), mask, now);
        return new UpdateResult(next, seq, true);
    }

    private static void backoff(int attempt) {
        long ms = Math.min(500, 20L << attempt) + ThreadLocalRandom.current().nextLong(20);
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}