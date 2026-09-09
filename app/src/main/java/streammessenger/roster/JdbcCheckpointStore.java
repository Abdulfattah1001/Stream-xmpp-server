package streammessenger.roster;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class JdbcCheckpointStore implements CheckpointStore {
    private static final String LOAD =
        "SELECT last_change_seq, updated_at FROM profile_sync_checkpoints WHERE recipient_user_id = ? AND device_id = ?";
    private static final String ADVANCE =
        "UPDATE profile_sync_checkpoints SET last_change_seq = ?, updated_at = ? " +
        "WHERE recipient_user_id = ? AND device_id = ? AND last_change_seq < ?";
    private static final String INSERT =
        "INSERT INTO profile_sync_checkpoints (recipient_user_id, device_id, last_change_seq, updated_at) VALUES (?, ?, ?, ?)";

    private final DataSource ds;
    public JdbcCheckpointStore(DataSource ds) { this.ds = ds; }

    @Override public Optional<ClientCheckpoint> load(String user, String device) throws SQLException {
        return Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(LOAD)) {
                ps.setString(1, user); ps.setString(2, device);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new ClientCheckpoint(user, device, rs.getLong(1), Jdbc.instant(rs.getTimestamp(2)))) : Optional.empty();
                }
            }
        });
    }

    /** Monotonic upsert: a late/duplicate ack can never move the checkpoint backwards. */
    @Override public void save(ClientCheckpoint cp) throws SQLException {
        Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(ADVANCE)) {
                ps.setLong(1, cp.lastChangeSeq()); ps.setTimestamp(2, Jdbc.ts(cp.updatedAt()));
                ps.setString(3, cp.recipientUserId()); ps.setString(4, cp.deviceId()); ps.setLong(5, cp.lastChangeSeq());
                if (ps.executeUpdate() == 1) return null;
            }
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                ps.setString(1, cp.recipientUserId()); ps.setString(2, cp.deviceId());
                ps.setLong(3, cp.lastChangeSeq()); ps.setTimestamp(4, Jdbc.ts(cp.updatedAt()));
                ps.executeUpdate();
            } catch (SQLException e) {
                if (!Jdbc.isDuplicateKey(e)) throw e;   // row exists with >= seq: nothing to do
            }
            return null;
        });
    }
}
