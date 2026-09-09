package streammessenger.roster;


import java.sql.*;
import java.util.*;

public final class JdbcProfileStore implements ProfileStore {
    private static final String COLS = "user_id, version, display_name, avatar_hash, status_text, metadata, updated_at";
    private static final String SELECT = "SELECT " + COLS + " FROM profiles WHERE user_id = ?";
    private static final String SELECT_FOR_UPDATE = SELECT + " FOR UPDATE";
    private static final String INSERT = "INSERT INTO profiles (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_CAS =
        "UPDATE profiles SET version = ?, display_name = ?, avatar_hash = ?, status_text = ?, metadata = ?, updated_at = ? " +
        "WHERE user_id = ? AND version = ?";
    private static final int IN_CHUNK = 500;

    @Override public Optional<Profile> find(Connection c, String userId) throws SQLException {
        return query(c, SELECT, userId);
    }
    @Override public Optional<Profile> lockForUpdate(Connection c, String userId) throws SQLException {
        return query(c, SELECT_FOR_UPDATE, userId);
    }

    private Optional<Profile> query(Connection c, String sql, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(read(rs)) : Optional.empty(); }
        }
    }

    private static Profile read(ResultSet rs) throws SQLException {
        return new Profile(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                           MetadataCodec.decode(rs.getString(6)), Jdbc.instant(rs.getTimestamp(7)));
    }

    @Override public void insert(Connection c, Profile p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, p.userId()); ps.setLong(2, p.version());
            ps.setString(3, p.displayName()); ps.setString(4, p.avatarHash()); ps.setString(5, p.statusText());
            ps.setString(6, MetadataCodec.encode(p.metadata())); ps.setTimestamp(7, Jdbc.ts(p.updatedAt()));
            ps.executeUpdate();
        }
    }

    @Override public boolean updateCas(Connection c, Profile next, long expectedVersion) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPDATE_CAS)) {
            ps.setLong(1, next.version()); ps.setString(2, next.displayName()); ps.setString(3, next.avatarHash());
            ps.setString(4, next.statusText()); ps.setString(5, MetadataCodec.encode(next.metadata()));
            ps.setTimestamp(6, Jdbc.ts(next.updatedAt())); ps.setString(7, next.userId()); ps.setLong(8, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    @Override public Map<String, Long> currentVersions(Connection c, Collection<String> userIds) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        List<String> ids = new ArrayList<>(userIds);
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
            String sql = "SELECT user_id, version FROM profiles WHERE user_id IN (" + Jdbc.placeholders(chunk.size()) + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i));
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.put(rs.getString(1), rs.getLong(2)); }
            }
        }
        return out;
    }
}
