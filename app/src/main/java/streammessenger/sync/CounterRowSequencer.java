package streammessenger.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Portable, commit-ordered sequencer. The UPDATE takes a row lock that is held until the
 * enclosing transaction commits, so seq order == commit order and pollers never see gaps.
 * Cost: one hot row; acceptable because profile edits are orders of magnitude rarer than messages.
 */
public class CounterRowSequencer {
    private static final String BUMP = "UPDATE profile_change_seq SET next_seq = next_seq + 1 WHERE id = 1";
    private static final String READ = "SELECT next_seq FROM profile_change_seq WHERE id = 1";

    public long next(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(BUMP)) {
            if(stmt.executeUpdate() != 1) throw new SQLException("profile_change_seq singleton row missing");
        }

        try(PreparedStatement stmt = c.prepareStatement(READ); ResultSet rs = stmt.executeQuery()) {
            if(!rs.next()) throw  new SQLException("profile_change_seq singletong row missing");
            return rs.getLong(1);
        }
    }

    public boolean commitOrdered() { return true; }
}
