package streammessenger.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import streammessenger.db.ConnectionPool;

public final class Jdbc {
    private Jdbc() {}

    @FunctionalInterface
    public interface SqlFunction<T> { T apply(Connection c) throws SQLException;}

    public static <T> T inTransaction(ConnectionPool pool, SqlFunction<T> body) throws SQLException {
        Connection c = pool.getConnection();
        boolean committed = false;
        try{
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            T result = body.apply(c);
            c.commit();
            committed = true;
            return result;
        }finally {
            if(!committed) {
                try{
                    c.rollback();
                }catch (SQLException ignored){}
                try{ c.setAutoCommit(true); } catch (SQLException ignored) {}
                c.close();
            }
        }
    }

    public static <T> T withConnection(ConnectionPool pool, SqlFunction<T> body) throws SQLException {
        try(Connection c = pool.getConnection()) {
            return body.apply(c);
        }
    }

    public static Timestamp ts(Instant i) { return Timestamp.from(i); }

    public static Instant instant(Timestamp t) { return t.toInstant(); }

    public static String placeholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for(int i = 0; i <  n; i++) {
            if(i > 0) sb.append(','); sb.append('?');
        }
        return sb.toString();
    }

    /** SQLSTATE class 23 = integrity constraint violattion (duplicate key, e.t.c.).*/
    public static boolean isDuplicateKey(SQLException e) {
        String s = e.getSQLState(); return s != null && s.startsWith("23");
    }

    /** SQLSTATE class 40 = serialization/dealock, 08 = connection failure; safe to retry the whole TX (Transaction)*/
    public static boolean isTransient(SQLException e) {
        String s = e.getSQLState(); return s != null && (s.startsWith("40") || s.startsWith("08"));
    }
}
