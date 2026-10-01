package streammessenger.roster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * DB-native sequence (no hot row, higher throughput) at the price of transient gaps
 * (a lower seq may commit after a higher one). Requires SequenceGapTracker in the worker.
 * sql example: PostgreSQL "SELECT nextval('profile_change_seq')", Oracle "SELECT profile_change_seq.NEXTVAL FROM dual".
 */
public final class NativeSequenceSequencer implements ChangeSequencer {
    private final String sql;
    public NativeSequenceSequencer(String sql) { this.sql = sql; }

    @Override public long next(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("sequence returned no row");
            return rs.getLong(1);
        }
    }
    @Override public boolean commitOrdered() { return false; }
}
