package streammessenger.roster;


import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public final class JdbcRosterStore implements RosterStore {
    private static final String CONTACTS = "SELECT contact_id FROM roster WHERE user_id = ? AND subscription IN ('to','both')";
    private static final String CAN_VIEW  = "SELECT 1 FROM roster WHERE user_id = ? AND contact_id = ? AND subscription IN ('from','both')";
    private static final int IN_CHUNK = 500;
    private final DataSource ds;
    public JdbcRosterStore(DataSource ds) { this.ds = ds; }

    @Override
    public Set<String> contactsOf(String userId) throws SQLException {
        return Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(CONTACTS)) {
                ps.setString(1, userId); ps.setFetchSize(1000);
                Set<String> out = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
                return out;
            }
        });
    }

    @Override public Set<String> filterContacts(String userId, Collection<String> candidates) throws SQLException {
        if (candidates.isEmpty()) return Set.of();
        List<String> ids = new ArrayList<>(candidates);
        return Jdbc.withConnection(ds, c -> {
            Set<String> out = new HashSet<>();
            for (int from = 0; from < ids.size(); from += IN_CHUNK) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
                String sql = CONTACTS + " AND contact_id IN (" + Jdbc.placeholders(chunk.size()) + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, userId);
                    for (int i = 0; i < chunk.size(); i++) ps.setString(i + 2, chunk.get(i));
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
                }
            }
            return out;
        });
    }

    /** owner's roster must list viewer with 'from'/'both' (owner shares presence/profile with viewer). Self always allowed. */
    @Override public boolean canView(String viewer, String owner) throws SQLException {
        if (viewer.equals(owner)) return true;
        return Jdbc.withConnection(ds, c -> {
            try (PreparedStatement ps = c.prepareStatement(CAN_VIEW)) {
                ps.setString(1, owner); ps.setString(2, viewer);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }
}
