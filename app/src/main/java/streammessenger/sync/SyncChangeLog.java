package streammessenger.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import streammessenger.db.ConnectionPool;

public class SyncChangeLog {
    private final String SELECT_FOR_UPDATE = "SELECT * FROM users WHERE user_id = ? FOR UPDATE";
    private static final String UPDATE_STATE =
            "UPDATE users SET version = ?, display_name = ?, avatar_url = ?," +
                    "username = ?, metadata = ?, display_status = ? WHERE user_id = ?";
    private static final String INSERT = "INSERT INTO changes (seq,  user_id, version, field_mask,changed_at) VALUES (?,?,?,?,?)";

    private static final String FETCH = "SELECT * FROM users WHERE user_id = ?";
    private static final String CURSOR_UPDATE = "UPDATE sync_node_cursors SET last_seq = ?, updated_at = ? WHERE node_id = ?";
    private static final String CURSOR_INSERT = "INSERT INTO sync_node_cursors (node_id, last_seq, updated_at) VALUES (?, ?, ?)";

    private static final String  READ_AFTER = "SELECT * FROM changes WHERE seq > ? ORDER BY seq";
    private static final String PRUNE = "DELETE FROM changes WHERE seq >= ? AND seq < ? AND changed_at < ?";

    private final static String MAX = "SELECT COALESCE(MAX(seq),0) FROM changes";
    private final static String MIN = "SELECT COALESCE(MIN(seq),0) FROM changes";
    private final int IN_CHUNK = 500;
    private final CounterRowSequencer sequencer;
    private final ConnectionPool pool;


    public SyncChangeLog(ConnectionPool pool, CounterRowSequencer sequencer) {
        this.pool = pool;  this.sequencer = sequencer;
    }

    public long append(Connection c, String userId, long version, int fieldMask, Instant at) throws SQLException {
        long seq = sequencer.next(c);
        try(PreparedStatement stmt = c.prepareStatement(INSERT)) {
            stmt.setLong(1, seq); stmt.setString(2, userId); stmt.setLong(3, version);
            stmt.setInt(4, fieldMask); stmt.setTimestamp(5, Jdbc.ts(at));
            stmt.executeUpdate();
        }
        return seq;
    }

    public List<Change> readAfter(long after, int limit) throws  SQLException {
        return Jdbc.withConnection(pool, c -> {
           try(PreparedStatement stmt = c.prepareStatement(READ_AFTER)) {
               stmt.setLong(1, after); stmt.setMaxRows(limit); stmt.setFetchSize(Math.min(limit, 1000));
               List<Change> out = new ArrayList<>();
               try(ResultSet rs = stmt.executeQuery()) {
                   while(rs.next()) out.add(new Change(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), Jdbc.instant(rs.getTimestamp(5))));
               }
               return out;
           }
        });
    }

    public long maxSeq() throws SQLException  { return scalar(MAX); }
    public long minSeq() throws SQLException { return scalar(MIN); }
    public List<Version> latestSince(long afterSeq, Collection<String> uids) throws  SQLException {
        if(uids.isEmpty()) return List.of();

        List<String> ids = new ArrayList<>(uids);
        return Jdbc.withConnection(pool, c -> {
            List<Version> out = new ArrayList<>();
            for(int from = 0; from < ids.size(); from += IN_CHUNK) {
                    List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
                    String sql = "SELECT user_id, MAX(version) FROM changes WHERE seq > ? AND user_id IN (" +
                            Jdbc.placeholders(chunk.size()) + ") GROUP BY user_id";
                    try(PreparedStatement stmt = c.prepareStatement(sql)) {
                        stmt.setLong(1, afterSeq);
                        for(int i = 0; i < chunk.size(); i++) stmt.setString(i + 2, chunk.get(i));
                        try(ResultSet rs = stmt.executeQuery()) { while(rs.next()) out.add(new Version(rs.getString(1), rs.getLong(2)));}
                    }
            }

            return  out;
        });
    }
    public int prune(Instant olderThan, int limit) throws SQLException {
        long min = minSeq();
        if (min == 0) return 0;
        return Jdbc.withConnection(pool, c -> {
            try(PreparedStatement stmt = c.prepareStatement(PRUNE)) {
                stmt.setLong(1, min); stmt.setLong(2, min + limit); stmt.setTimestamp(3, Jdbc.ts(olderThan));
                return stmt.executeUpdate();
            }
        });
    }
    public void recordNodeCursor(String nodeId, long seq) throws  SQLException {
        Jdbc.withConnection(pool, c -> {
            Timestamp now = streammessenger.roster.Jdbc.ts(Instant.now());
            try (PreparedStatement ps = c.prepareStatement(CURSOR_UPDATE)) {
                ps.setLong(1, seq); ps.setTimestamp(2, now); ps.setString(3, nodeId);
                if (ps.executeUpdate() == 1) return null;
            }
            try (PreparedStatement ps = c.prepareStatement(CURSOR_INSERT)) {
                ps.setString(1, nodeId); ps.setLong(2, seq); ps.setTimestamp(3, now);
                ps.executeUpdate();
            } catch (SQLException e) { if (!streammessenger.roster.Jdbc.isDuplicateKey(e)) throw e; }
            return null;
        });
    }


    private long scalar(String sql) throws SQLException {
        return Jdbc.withConnection(pool, c -> {
            try(PreparedStatement stmt = c.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
