package streammessenger.roster;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface ProfileStore {
    Optional<Profile> find(Connection c, String userId) throws SQLException;
    /** Row-locks the profile for the remainder of the transaction. */
    Optional<Profile> lockForUpdate(Connection c, String userId) throws SQLException;
    void insert(Connection c, Profile initial) throws SQLException;
    /** Compare-and-set on version. Returns false if the row's version != expectedVersion. */
    boolean updateCas(Connection c, Profile next, long expectedVersion) throws SQLException;
    /** Version-only lookup for full reconciliation. Missing users are absent from the map. */
    Map<String, Long> currentVersions(Connection c, Collection<String> userIds) throws SQLException;
}
