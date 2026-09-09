package streammessenger.roster;

import java.time.Instant;
import java.util.Objects;

/** Per-device recipient progress: highest change seq fully applied by that device. */
public record ClientCheckpoint(String recipientUserId, String deviceId, long lastChangeSeq, Instant updatedAt) {
    public ClientCheckpoint {
        Objects.requireNonNull(recipientUserId); Objects.requireNonNull(deviceId); Objects.requireNonNull(updatedAt);
        if (lastChangeSeq < 0) throw new IllegalArgumentException("lastChangeSeq must be >= 0");
    }
    public record Key(String recipientUserId, String deviceId) {}
    public Key key() { return new Key(recipientUserId, deviceId); }
}
