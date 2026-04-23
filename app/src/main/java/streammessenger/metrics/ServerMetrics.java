package streammessenger.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Lock-free, thread-safe server metrics.
 *
 * LongAdder is used for high-write counters (better than AtomicLong under contention).
 * AtomicLong is used for gauges that need precise reads (like active connections).
 *
 * In production: expose these via JMX MBean, 
 * a /metrics HTTP endpoint, or Prometheus JVM agent.
 */
public final class ServerMetrics {

    private static final Logger logger = Logger.getLogger(ServerMetrics.class.getName());
    private static final ServerMetrics INSTANCE = new ServerMetrics();

    // Gauges
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong authenticatedSessions = new AtomicLong(0);

    // Counters
    private final LongAdder totalConnectionsAccepted = new LongAdder();
    private final LongAdder totalConnectionsRejected = new LongAdder();
    private final LongAdder tlsUpgradesSucceeded = new LongAdder();
    private final LongAdder tlsUpgradesFailed = new LongAdder();
    private final LongAdder sessionsReaped = new LongAdder();
    private final LongAdder authSuccesses = new LongAdder();
    private final LongAdder authFailures = new LongAdder();
    private final LongAdder stanzasProcessed = new LongAdder();
    private final LongAdder messagesSent = new LongAdder();
    private final LongAdder messagesOfflineStored = new LongAdder();

    private ServerMetrics() {}

    public static ServerMetrics getInstance() { return INSTANCE; }

    public void connectionAccepted() {
        activeConnections.incrementAndGet();
        totalConnectionsAccepted.increment();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public void connectionRejected() {
        totalConnectionsRejected.increment();
    }

    public void sessionAuthenticated() {
        authenticatedSessions.incrementAndGet();
        authSuccesses.increment();
    }

    public void sessionDeAuthenticated() {
        authenticatedSessions.decrementAndGet();
    }

    public void authFailure() { authFailures.increment(); }
    public void tlsSuccess() { tlsUpgradesSucceeded.increment(); }
    public void tlsFailure() { tlsUpgradesFailed.increment(); }
    public void sessionReaped() { sessionsReaped.increment(); }
    public void stanzaProcessed() { stanzasProcessed.increment(); }
    public void messageSent() { messagesSent.increment(); }
    public void messageStoredOffline() { messagesOfflineStored.increment(); }

    // Snapshot reads
    public long getActiveConnections() { return activeConnections.get(); }
    public long getAuthenticatedSessions() { return authenticatedSessions.get(); }
    public long getTotalAccepted() { return totalConnectionsAccepted.sum(); }
    public long getTotalRejected() { return totalConnectionsRejected.sum(); }
    public long getTlsSuccesses() { return tlsUpgradesSucceeded.sum(); }
    public long getAuthSuccesses() { return authSuccesses.sum(); }
    public long getStanzasProcessed() { return stanzasProcessed.sum(); }

    /**
     * Logs a summary of current metrics.
     */
    public void logSummary() {
        logger.info(toString());
    }

    @Override
    public String toString() {
        return String.format(
            "ServerMetrics{" +
            "activeConn=%d, authenticatedSessions=%d, " +
            "totalAccepted=%d, rejected=%d, " +
            "tlsOk=%d, tlsFail=%d, " +
            "authOk=%d, authFail=%d, " +
            "reaped=%d, stanzas=%d, " +
            "msgSent=%d, msgOffline=%d}",
            activeConnections.get(), authenticatedSessions.get(),
            totalConnectionsAccepted.sum(), totalConnectionsRejected.sum(),
            tlsUpgradesSucceeded.sum(), tlsUpgradesFailed.sum(),
            authSuccesses.sum(), authFailures.sum(),
            sessionsReaped.sum(), stanzasProcessed.sum(),
            messagesSent.sum(), messagesOfflineStored.sum()
        );
    }
}