package streammessenger.profile;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import streammessenger.roster.ProfileFields;

public record Profile(
        String userId,
        String username,
        long version,
        String displayName,
        String avatarUrl,
        String statusText,
        Map<String, String> metadata,
        Instant at
){
    private static final int MAX_DISPLAY_NAME = 255;
    private static final int MAX_AVATAR_HASH = 128;
    private static final int MAX_STATUS = 1024;
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_KEY = 64;
    private static final int MAX_METADATA_VALUES = 2048;

    /** Which fields differ (ignores version/updatedAt). 0 == semantically identical. */
    public int diffMask(Profile other) {
        int m = 0;
        if (!Objects.equals(displayName, other.displayName)) m |= ProfileFields.DISPLAY_NAME;
        if (!Objects.equals(avatarUrl, other.avatarUrl)) m |= ProfileFields.AVATAR;
        if (!Objects.equals(statusText, other.statusText)) m |= ProfileFields.STATUS;
        if (!metadata.equals(other.metadata)) m |= ProfileFields.METADATA;
        return m;
    }

    public Profile withVersion(long newVersion, Instant at) {
        return new Profile(userId, username, newVersion, displayName, avatarUrl, statusText, metadata, at);
    }
}