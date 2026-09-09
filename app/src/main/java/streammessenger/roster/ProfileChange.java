package streammessenger.roster;

import java.time.Instant;
import java.util.Objects;

/** One row of the append-only change log. seq is global; version is per-user. */
public record ProfileChange(long seq, String userId, long version, int fieldMask, Instant changedAt) {
    public ProfileChange {
        Objects.requireNonNull(userId); Objects.requireNonNull(changedAt);
        if (seq <= 0) throw new IllegalArgumentException("seq must be > 0");
    }
    public ProfileVersion ref() { return new ProfileVersion(userId, version); }
}
