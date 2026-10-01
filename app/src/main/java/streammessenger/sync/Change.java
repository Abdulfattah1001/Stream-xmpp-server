package streammessenger.sync;

import java.time.Instant;
import java.util.Objects;

public record Change(long seq, String userId, long version, int field, Instant changedAt){
    public Change {
        Objects.requireNonNull(userId); Objects.requireNonNull(changedAt);
        if(seq < 0) throw new IllegalArgumentException("seq must be greater than 0");
    }

    public Version ref() { return new Version(userId, version); }
}
