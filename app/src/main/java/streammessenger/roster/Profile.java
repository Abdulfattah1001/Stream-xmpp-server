package streammessenger.roster;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable durable profile state. Avatar is referenced by content hash, never embedded. */
public record Profile(
        String userId,
        long version,
        String displayName,
        String avatarHash,
        String statusText,
        Map<String, String> metadata,
        Instant updatedAt) {

    public static final int MAX_DISPLAY_NAME = 255;
    public static final int MAX_AVATAR_HASH = 128;
    public static final int MAX_STATUS = 1024;
    public static final int MAX_METADATA_ENTRIES = 64;
    public static final int MAX_METADATA_KEY = 64;
    public static final int MAX_METADATA_VALUE = 2048;

    public Profile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must be >= 0");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        check(displayName, MAX_DISPLAY_NAME, "displayName");
        check(avatarHash, MAX_AVATAR_HASH, "avatarHash");
        check(statusText, MAX_STATUS, "statusText");
        if (metadata.size() > MAX_METADATA_ENTRIES) throw new IllegalArgumentException("too many metadata entries");
        for (var e : metadata.entrySet()) {
            check(e.getKey(), MAX_METADATA_KEY, "metadata key");
            check(e.getValue(), MAX_METADATA_VALUE, "metadata value");
        }
    }

    private static void check(String s, int max, String what) {
        if (s != null && s.length() > max) throw new IllegalArgumentException(what + " exceeds " + max + " chars");
    }

    public static Profile initial(String userId, Instant now) {
        return new Profile(userId, 0, null, null, null, Map.of(), now);
    }

    public ProfileVersion ref() { return new ProfileVersion(userId, version); }

    public Profile withVersion(long newVersion, Instant at) {
        return new Profile(userId, newVersion, displayName, avatarHash, statusText, metadata, at);
    }

    /** Which fields differ (ignores version/updatedAt). 0 == semantically identical. */
    public int diffMask(Profile other) {
        int m = 0;
        if (!Objects.equals(displayName, other.displayName)) m |= ProfileFields.DISPLAY_NAME;
        if (!Objects.equals(avatarHash, other.avatarHash)) m |= ProfileFields.AVATAR;
        if (!Objects.equals(statusText, other.statusText)) m |= ProfileFields.STATUS;
        if (!metadata.equals(other.metadata)) m |= ProfileFields.METADATA;
        return m;
    }
}
