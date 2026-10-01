package streammessenger.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import streammessenger.roster.Jdbc;

public final class ProfileStore {
    private static final String COLS = "user_id, version, display_name, avatar_url, display_status, metadata, updated_at";
    private static final String SELECT = "SELECT " + COLS + " FROM users WHERE user_id = ?";
    private static final String SELECT_FOR_UPDATE = SELECT + " FOR UPDATE";
    private static final String INSERT = "INSERT INTO users (" + COLS + ") VALUES VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_CAS =
            "UPDATE users SET version = ?, display_name = ?, avatar_url = ?, display_status = ?, metadata = ?, updated_at = ? " +
            "WHERE user_id = ? AND version = ?";
    private static final int IN_CHUNK = 500;

    public Optional<Profile> find(Connection c, String userId) throws SQLException {
        return query(c, SELECT, userId);
    }

    public void insert(Connection c, Profile p) throws  SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, p.userId()); ps.setLong(2, p.version());
            ps.setString(3, p.displayName()); ps.setString(4, p.avatarUrl()); ps.setString(5, p.statusText());
            ps.setString(6, MetadataCodec.encode(p.metadata())); ps.setTimestamp(7, Jdbc.ts(p.at()));
            ps.executeUpdate();
        }
    }

    public boolean updateCase(Connection c, Profile next, long expectedVersion) throws  SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPDATE_CAS)) {
            ps.setLong(1, next.version()); ps.setString(2, next.displayName()); ps.setString(3, next.avatarUrl());
            ps.setString(4, next.statusText()); ps.setString(5, MetadataCodec.encode(next.metadata()));
            ps.setTimestamp(6, Jdbc.ts(next.at())); ps.setString(7, next.userId()); ps.setLong(8, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    private Optional<Profile> query(Connection c, String sql, String userId) throws SQLException {
        try(PreparedStatement stmt = c.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private Profile read(ResultSet rs)  throws SQLException {
        return new Profile(rs.getString(1), "", rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                MetadataCodec.decode(rs.getString(6)), Jdbc.instant(rs.getTimestamp(7)));
    }

    public Optional<Profile> lockForUpdate(Connection c, String userId) throws  SQLException {
        return query(c, SELECT_FOR_UPDATE, userId);
    }
}