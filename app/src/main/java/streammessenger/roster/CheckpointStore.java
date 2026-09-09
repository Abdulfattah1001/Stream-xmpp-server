package streammessenger.roster;

import java.sql.SQLException;
import java.util.Optional;

public interface CheckpointStore {
    Optional<ClientCheckpoint> load(String recipientUserId, String deviceId) throws SQLException;
    /** Upsert; only advances (never regresses) the stored seq. */
    void save(ClientCheckpoint checkpoint) throws SQLException;
}
