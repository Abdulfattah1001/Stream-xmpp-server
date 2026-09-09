package streammessenger.roster;



import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JdbcProfileChangeLog implements ProfileChangeLog {
    private static final String INSERT =
        "INSERT INTO profile_changes (seq, user_id, version, field_mask, changed_at) VALUES (?, ?, ?, ?, ?)";
    private static final String READ_AFTER =
        "SELECT seq, user_id, version, field_mask, changed_at FROM profile_changes WHERE seq > ? ORDER BY seq";
    private static final String MAX_SEQ = "SELECT COALESCE(MAX(seq), 0) FROM profile_changes";
    private static final String MIN_SEQ = "SELECT COALESCE(MIN(seq), 0) FROM profile_changes";
    private static final String PRUNE = "DELETE FROM profile_changes WHERE seq >= ? AND seq < ? AND changed_at < ?";
    private static final String CURSOR_UPDATE = "UPDATE sync_node_cursors SET last_seq = ?, updated_at = ? WHERE node_id = ?";
    private static final String CURSOR_INSERT = "INSERT INTO sync_node_cursors (node_id, last_seq, updated_at) VALUES (?, ?, ?)";
    private static final int IN_CHUNK = 500;

    private final DataSource ds;
    private final ChangeSequencer sequencer;

    public JdbcProfileChangeLog(DataSource ds, ChangeSequencer sequencer) {
        this.ds = Objects.requireNonNull(ds); this.sequencer = Objects.requireNonNull(sequencer);
    }
    public ChangeSequencer sequencer() { return sequencer; }

    @Override public long append(Connection c, String userId, long version, int fieldMask, Instant at) throws SQLException {
        long seq = sequencer.next(c);
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, seq); ps.setString(2, userId); ps.setLong(3, version); ps.setInt(4, fieldMask); ps.setTimestamp(5, Jdbc.ts(at));
            ps.executeUpdate();
        }
        return seq;
    }

    @Override public List<ProfileChange> readAfter(long afterSeq, int limit) throws SQLException {
        return Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(READ_AFTER)) {
                ps.setLong(1, afterSeq);
                ps.setMaxRows(limit);          // portable LIMIT
                ps.setFetchSize(Math.min(limit, 1000));
                List<ProfileChange> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ProfileChange(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), Jdbc.instant(rs.getTimestamp(5))));
                }
                return out;
            }
        });
    }

    @Override public long maxSeq() throws SQLException { return scalar(MAX_SEQ); }
    @Override public long minSeq() throws SQLException { return scalar(MIN_SEQ); }

    private long scalar(String sql) throws SQLException {
        return Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        });
    }

    @Override public List<ProfileVersion> latestSince(long afterSeq, Collection<String> userIds) throws SQLException {
        if (userIds.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>(userIds);
        return Jdbc.withConnection(ds, c -> {
            List<ProfileVersion> out = new ArrayList<>();
            for (int from = 0; from < ids.size(); from += IN_CHUNK) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
                String sql = "SELECT user_id, MAX(version) FROM profile_changes WHERE seq > ? AND user_id IN ("
                           + Jdbc.placeholders(chunk.size()) + ") GROUP BY user_id";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, afterSeq);
                    for (int i = 0; i < chunk.size(); i++) ps.setString(i + 2, chunk.get(i));
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(new ProfileVersion(rs.getString(1), rs.getLong(2))); }
                }
            }
            return out;
        });
    }

    @Override public int prune(Instant olderThan, int limit) throws SQLException {
        long min = minSeq();
        if (min == 0) return 0;
        return Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(PRUNE)) {   // bounded by seq range → small, index-friendly batches
                ps.setLong(1, min); ps.setLong(2, min + limit); ps.setTimestamp(3, Jdbc.ts(olderThan));
                return ps.executeUpdate();
            }
        });
    }

    @Override public void recordNodeCursor(String nodeId, long seq) throws SQLException {
        Jdbc.withConnection(ds, c -> {
            Timestamp now = Jdbc.ts(Instant.now());
            try (PreparedStatement ps = c.prepareStatement(CURSOR_UPDATE)) {
                ps.setLong(1, seq); ps.setTimestamp(2, now); ps.setString(3, nodeId);
                if (ps.executeUpdate() == 1) return null;
            }
            try (PreparedStatement ps = c.prepareStatement(CURSOR_INSERT)) {
                ps.setString(1, nodeId); ps.setLong(2, seq); ps.setTimestamp(3, now);
                ps.executeUpdate();
            } catch (SQLException e) { if (!Jdbc.isDuplicateKey(e)) throw e; }
            return null;
        });
    }
}
