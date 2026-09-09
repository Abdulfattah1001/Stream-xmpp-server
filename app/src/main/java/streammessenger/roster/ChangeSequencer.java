package streammessenger.roster;

import java.sql.Connection;
import java.sql.SQLException;

/** Strategy for assigning change-log sequence numbers inside the update transaction. */
public interface ChangeSequencer {
    long next(Connection c) throws SQLException;
    /** true if seq order is guaranteed to equal commit order (no transient gaps for pollers). */
    boolean commitOrdered();
}
