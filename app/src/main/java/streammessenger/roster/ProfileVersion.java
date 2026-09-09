package streammessenger.roster;

import java.util.Objects;

public record ProfileVersion(String userId, long version) implements Comparable<ProfileVersion> {
    public ProfileVersion {
        Objects.requireNonNull(userId, "userId");
        if(version < 0) throw new IllegalArgumentException("Version number must be >= 0");
    }

    public boolean isNewerThan(long other) {
        return version > other;
    }

    @Override
    public int compareTo(ProfileVersion o) {
        int c = userId.compareTo(o.userId);
        return c != 0 ? c : Long.compare(version, o.version);
    }
}
