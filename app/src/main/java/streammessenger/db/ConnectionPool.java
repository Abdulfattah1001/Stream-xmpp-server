package streammessenger.db;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import streammessenger.config.ServerConfig;

import java.sql.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom JDBC connection pool - no external dependencies.
 * <p>
 * Design:
 *   - Fixed-size pool: min connections always open, grows to max under load
 *   - BlockingQueue holds available connections
 *   - Callers block up to connectionTimeoutMs waiting for one
 *   - Validation on borrow: dead connections are replaced transparently
 *   - Background evictor removes connections that have been idle too long
 * <p>
 * This replaces HikariCP with a simpler implementation that covers
 * all the cases is actually need.
 */
public final class ConnectionPool {

    private static final Logger logger = Logger.getLogger(ConnectionPool.class.getName());

    private final String jdbcUrl;
    private final String username;
    private final char[] password;
    private final int minConnections;
    private final int maxConnections;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final String validationQuery;

    // Pool of available (idle) connections
    private final BlockingQueue<PooledConnection> available;

    // Total connections currently open (idle + in-use)
    private final AtomicInteger totalConnections = new AtomicInteger(0);

    // Background thread to evict stale idle connections
    private final java.util.concurrent.ScheduledExecutorService evictor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "db-pool-evictor");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean closed = false;

    public ConnectionPool(ServerConfig config) {
        this.jdbcUrl = config.getDbUrl();
        this.username = config.getDbUser();

        // Password from environment - never from config object
        String dbPass = System.getenv("DB_PASSWORD");

        if (dbPass == null || dbPass.isEmpty()) {
            throw new IllegalStateException(
                    "DB_PASSWORD environment variable not set");
        }
        this.password = dbPass.toCharArray();

        this.minConnections = config.getDbPoolMin();
        this.maxConnections = config.getDbPoolMax();
        this.connectionTimeoutMs = config.getDbPoolTimeout();
        this.idleTimeoutMs = 600_000;    // 10 minutes
        this.maxLifetimeMs = 1_800_000;  // 30 minutes
        this.validationQuery = "SELECT 1";

        this.available = new ArrayBlockingQueue<>(maxConnections);

        // Pre-warm minimum connections
        initMinConnections();

        // Evict idle connections every 2 minutes
        evictor.scheduleAtFixedRate(
                this::evictIdleConnections, 2, 2, TimeUnit.MINUTES);

        logger.info(String.format(
                "Connection pool initialized [url=%s, min=%d, max=%d, timeout=%dms]",
                jdbcUrl, minConnections, maxConnections, connectionTimeoutMs
        ));
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Borrows a connection from the pool.
     * <p>
     * Returns immediately if a valid connection is available.
     * Creates a new connection if pool is below max.
     * Blocks up to connectionTimeoutMs if pool is at max.
     *
     * @throws SQLException if no connection is available within timeout,
     *                      or if a new connection cannot be created
     */
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("Connection pool is closed");
        }

        // Try to get an idle connection without blocking first
        PooledConnection pooled = available.poll();

        if (pooled != null) {
            if (isValid(pooled)) {
                pooled.markBorrowed();
                return pooled.getConnection();
            } else {
                // Connection is dead - close it and get another
                closeQuietly(pooled);
                totalConnections.decrementAndGet();
                return getConnection(); // Recurse to try again
            }
        }

        // No idle connection available
        // Can we create a new one?
        if (totalConnections.get() < maxConnections) {
            PooledConnection newConn = createConnection();
            newConn.markBorrowed();
            return newConn.getConnection();
        }

        // Pool is at max - wait for one to be returned
        try {
            pooled = available.poll(connectionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for connection");
        }

        if (pooled == null) {
            throw new SQLException(String.format(
                    "Connection pool timeout after %dms. " +
                            "Pool: total=%d, max=%d, idle=%d",
                    connectionTimeoutMs,
                    totalConnections.get(),
                    maxConnections,
                    available.size()
            ));
        }

        if (!isValid(pooled)) {
            closeQuietly(pooled);
            totalConnections.decrementAndGet();
            return getConnection();
        }

        pooled.markBorrowed();
        return pooled.getConnection();
    }

    /**
     * Returns a connection to the pool.
     * <p>
     * This is called automatically when the caller closes the Connection
     * (via try-with-resources). The PooledConnection wrapper intercepts
     * close() and returns it to the pool instead of actually closing it.
     */
    public void returnConnection(PooledConnection pooled) {
        if (closed) {
            closeQuietly(pooled);
            totalConnections.decrementAndGet();
            return;
        }

        try {
            // Reset state before returning
            if (!pooled.getConnection().getAutoCommit()) {
                pooled.getConnection().rollback(); // Rollback any uncommitted transaction
                pooled.getConnection().setAutoCommit(false);
            }
        } catch (SQLException e) {
            // Connection is in bad state - discard it
            logger.warning("Connection in bad state on return - discarding: "
                    + e.getMessage());
            closeQuietly(pooled);
            totalConnections.decrementAndGet();
            return;
        }

        // Check max lifetime
        if (System.currentTimeMillis() - pooled.getCreatedAt() > maxLifetimeMs) {
            logger.fine("Connection exceeded max lifetime - discarding");
            closeQuietly(pooled);
            totalConnections.decrementAndGet();
            // Replenish to maintain min
            maintainMinConnections();
            return;
        }

        pooled.markIdle();

        if (!available.offer(pooled)) {
            // Queue is full (shouldn't happen if max is configured correctly)
            closeQuietly(pooled);
            totalConnections.decrementAndGet();
        }
    }

    public void shutdown() {
        closed = true;
        evictor.shutdown();

        PooledConnection conn;
        while ((conn = available.poll()) != null) {
            closeQuietly(conn);
            totalConnections.decrementAndGet();
        }

        // Zero out password from memory
        java.util.Arrays.fill(password, '\0');

        logger.info("Connection pool shutdown. Remaining open: "
                + totalConnections.get());
    }

    public int getActiveCount() {
        return totalConnections.get() - available.size();
    }

    public int getIdleCount() {
        return available.size();
    }

    public int getTotalCount() {
        return totalConnections.get();
    }

    // =========================================================================
    // Private - connection lifecycle
    // =========================================================================

    private void initMinConnections() {
        for (int i = 0; i < minConnections; i++) {
            try {
                PooledConnection conn = createConnection();
                conn.markIdle();
                available.offer(conn);
            } catch (SQLException e) {
                logger.warning("Failed to pre-warm connection " + i
                        + ": " + e.getMessage());
            }
        }
    }

    private PooledConnection createConnection() throws SQLException {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", username);
        props.setProperty("password", new String(password));

        // PostgresSQL specific optimizations
        props.setProperty("reWriteBatchedInserts", "true");
        props.setProperty("defaultRowFetchSize", "50");
        props.setProperty("loginTimeout", "10");

        Connection raw = DriverManager.getConnection(jdbcUrl, props);
        raw.setAutoCommit(false);

        totalConnections.incrementAndGet();

        logger.fine("Created connection. Total: " + totalConnections.get());

        return new PooledConnection(raw, this);
    }

    private boolean isValid(PooledConnection pooled) {
        try {
            Connection conn = pooled.getConnection();
            if (conn.isClosed()) return false;
            // Validate with a lightweight query
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(2); // 2 second timeout
                stmt.execute(validationQuery);
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void evictIdleConnections() {
        long now = System.currentTimeMillis();
        int evicted = 0;

        // Temporarily drain the queue, put back non-evictable ones
        int size = available.size();
        for (int i = 0; i < size; i++) {
            PooledConnection conn = available.poll();
            if (conn == null) break;

            boolean shouldEvict =
                    (now - conn.getLastUsedAt() > idleTimeoutMs)
                            || (now - conn.getCreatedAt() > maxLifetimeMs)
                            || !isValid(conn);

            if (shouldEvict && totalConnections.get() > minConnections) {
                closeQuietly(conn);
                totalConnections.decrementAndGet();
                evicted++;
            } else {
                available.offer(conn);
            }
        }

        if (evicted > 0) {
            logger.fine("Pool evictor removed " + evicted
                    + " idle connections. Active=" + getActiveCount()
                    + " Idle=" + getIdleCount());
            maintainMinConnections();
        }
    }

    private void maintainMinConnections() {
        while (totalConnections.get() < minConnections && !closed) {
            try {
                PooledConnection conn = createConnection();
                conn.markIdle();
                available.offer(conn);
            } catch (SQLException e) {
                logger.warning("Failed to maintain min connections: " + e.getMessage());
                break;
            }
        }
    }

    private void closeQuietly(PooledConnection pooled) {
        try {
            pooled.forceClose();
        } catch (SQLException ignored) {}
    }

    // =========================================================================
    // PooledConnection - wraps a real JDBC Connection
    // =========================================================================

    /**
     * Wraps a real JDBC Connection.
     *
     * Intercepts close() to return to pool instead of actually closing.
     * Tracks creation time and last-used time for eviction logic.
     */
    public static final class PooledConnection implements java.lang.reflect.InvocationHandler {

        private final Connection realConnection;
        private final ConnectionPool pool;
        private final long createdAt;
        private volatile long lastUsedAt;
        private volatile boolean borrowed = false;

        // The proxy that callers interact with
        private final Connection proxy;

        public PooledConnection(Connection realConnection, ConnectionPool pool) {
            this.realConnection = realConnection;
            this.pool = pool;
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = createdAt;

            // Create a proxy that intercepts close()
            this.proxy = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class[]{Connection.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method,
                             Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                // Return to pool instead of closing
                pool.returnConnection(this);
                return null;
            }
            if ("isClosed".equals(method.getName())) {
                return realConnection.isClosed();
            }
            // All other methods delegate to the real connection
            lastUsedAt = System.currentTimeMillis();
            return method.invoke(realConnection, args);
        }

        public Connection getConnection() {
            return proxy;
        }

        public long getCreatedAt() { return createdAt; }
        public long getLastUsedAt() { return lastUsedAt; }

        public void markBorrowed() {
            borrowed = true;
            lastUsedAt = System.currentTimeMillis();
        }

        public void markIdle() {
            borrowed = false;
            lastUsedAt = System.currentTimeMillis();
        }

        /** Actually closes the underlying connection - bypasses pool return. */
        public void forceClose() throws SQLException {
            realConnection.close();
        }
    }
}