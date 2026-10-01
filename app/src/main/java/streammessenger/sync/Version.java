package streammessenger.sync;

import java.util.Objects;

public record Version(String userId, long version) implements Comparable<Version> {

    public Version {
        Objects.requireNonNull(userId, "userId");
        if( version < 0) throw  new IllegalArgumentException("Version number must be greater than 0");
    }
    @Override
    public int compareTo(Version o) {
        int c = userId.compareTo(o.userId);
        return c != 0 ? c : Long.compare(version, o.version);
    }
}
