package streammessenger.sync;

import java.util.concurrent.atomic.AtomicBoolean;

import streammessenger.Server;
import streammessenger.metrics.ServerMetrics;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 *
 */
public class SyncWorker implements Runnable{
    private final SessionRegistry registry;
    private final ServerMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SyncWorker(SessionRegistry registry, ServerMetrics metrics) {
        this.registry = registry; this.metrics = metrics;
    }

    public synchronized void start() {
        if(!running.compareAndSet(false, true)) return;

    }
    @Override
    public void run(){}
}