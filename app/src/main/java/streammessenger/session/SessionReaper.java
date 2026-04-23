package streammessenger.session;


import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import streammessenger.exception.StreamException;
import streammessenger.metrics.ServerMetrics;

/**
 * Background task that periodically sweeps all sessions and closes idle ones.
 *
 * A session is idle if:
 * 1. It is fully authenticated, AND
 * 2. Its lastActivity timestamp exceeds the configured timeout
 *
 * Unauthenticated sessions that have been open too long are also 
 * reaped to prevent resource exhaustion from half-open connections.
 */
public final class SessionReaper {

    private static final Logger logger = Logger.getLogger(SessionReaper.class.getName());

    private final SessionRegistry registry;
    private final ServerMetrics metrics;
    private final long sessionTimeoutMs;
    private final long unauthTimeoutMs; // Shorter timeout for unauthenticated sessions
    private final long intervalSec;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-reaper");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> taskHandle;

    public SessionReaper(SessionRegistry registry, ServerMetrics metrics,
                         long sessionTimeoutMs, long intervalSec) {
        this.registry = registry;
        this.metrics = metrics;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.unauthTimeoutMs = 30_000; // 30 seconds for unauthenticated sessions
        this.intervalSec = intervalSec;
    }

    public void start() {
        taskHandle = scheduler.scheduleAtFixedRate(
                this::sweep,
                intervalSec,
                intervalSec,
                TimeUnit.SECONDS
        );
        logger.info(String.format(
            "Session reaper started [interval=%ds, authTimeout=%dms, unauthTimeout=%dms]",
            intervalSec, sessionTimeoutMs, unauthTimeoutMs
        ));
    }

    public void stop() {
        if (taskHandle != null) {
            taskHandle.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Session reaper stopped.");
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        int reaped = 0;

        for (Session session : registry.getAllSessions()) {
            try {
                if (session.isClosed()) {
                    // Already closed, just remove from registry
                    registry.remove(session);
                    continue;
                }

                long idle = now - session.getLastActivity();
                boolean shouldReap;

                if (session.isAuthenticated()) {
                    shouldReap = idle > sessionTimeoutMs;
                } else {
                    // Unauthenticated sessions get a much shorter timeout
                    shouldReap = idle > unauthTimeoutMs;
                }

                if (shouldReap) {
                    logger.info(String.format(
                        "Reaping session uid=%s contactId=%s idle=%dms authenticated=%b",
                        session.getUid(), session.getContactId(), idle, session.isAuthenticated()
                    ));

                    session.writeStreamError(
                        StreamException.Condition.CONNECTION_TIMEOUT,
                        "Session idle timeout"
                    );
                    registry.remove(session);
                    session.closeQuietly();
                    metrics.sessionReaped();
                    reaped++;
                }

            } catch (Exception e) {
                // Isolation: one bad session must not crash the reaper
                logger.warning("Reaper error for session uid="
                        + session.getUid() + ": " + e.getMessage());
            }
        }

        if (reaped > 0 || logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.info("Reaper sweep complete. Reaped=" + reaped
                    + " Active=" + registry.size());
        }
    }
}