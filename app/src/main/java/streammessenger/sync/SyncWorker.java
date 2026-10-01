package streammessenger.sync;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

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
    private Thread thread;
    private final SyncChangeLog log;
    private final Semaphore wake = new Semaphore(0);
    private final Consumer<Change> changeObserver;
    private volatile  GapTracker tracker;
    private final static Logger logger = Logger.getLogger(SyncWorker.class.getName());

    public SyncWorker(SessionRegistry registry, ServerMetrics metrics, SyncChangeLog log, Consumer<Change> changeObserver) {
        this.registry = registry; this.metrics = metrics;
        this.log = log; this.changeObserver = changeObserver;
    }

    public synchronized void start() throws SQLException {
        if(!running.compareAndSet(false, true)) return;
        long start = log.maxSeq();
        metrics.setLowWatermark(start);
        metrics.setMaxSeqSeen(start);
        tracker = new GapTracker(start, 5000, 1000);
        thread = new Thread(this, "sync-worker");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop(long timeoutMs) throws InterruptedException {
        if(!running.compareAndSet(true, false)) return;
        wake.release();
        thread.join(timeoutMs);
        if(thread.isAlive()) thread.interrupt();
    }

    /**
     * Local wake-up after a commit of a profile change.
     */
    public void  hint(long seq) { System.out.println("Hint called ..."); if(wake.availablePermits() == 0) wake.release(); }

    private boolean pollOnce() throws SQLException {
        long now = System.nanoTime();
        metrics.workerPolls();
        List<Change> rows = log.readAfter(tracker.lowWatermark(), 500);

        logger.info("Poll size is: "+rows.size());

        if(rows.isEmpty()) {
            tracker.expire(now);
            return false;
        }

        LinkedHashMap<String, Change> latest = new LinkedHashMap<>();
        List<Long> seqs = new ArrayList<>(rows.size());
        for(Change ch : rows) {
            if(tracker.isDone(ch.seq())) continue;
            seqs.add(ch.seq());
            Change prev = latest.put(ch.userId(), ch);
            if(prev != null){
                if(prev.version() > ch.version()) latest.put(prev.userId(), prev);
            }
        }

        tracker.observeMax(rows.get(rows.size() -1).seq(), now);
        dispatch(latest.values());

        for(long s : seqs) tracker.markDonw(s);
        return rows.size() >= 500;
    }

    private void dispatch(Collection<Change> changes) {
        for(Change ch : changes) {
            if(changeObserver  != null) changeObserver.accept(ch);
            for(Session s : registry.interestedIn(ch.userId())){
                if(s.getUid().equals(ch.userId())) continue;
                logger.info("Notifying user: "+s.getUid());
                s.invalidate(ch.userId(), ch.version(), ch.seq());
            }
        }
    }

    @Override
    public void run(){
        long backOff = 5000L;
        while(running.get()){
            try{
                boolean more = pollOnce();
                backOff = 5000L;
                if(!more){
                    wake.tryAcquire(5000L, TimeUnit.SECONDS);
                    wake.drainPermits();
                }
            }catch (InterruptedException exception){
                Thread.currentThread().interrupt(); return;
            } catch (SQLException | RuntimeException e){
                e.printStackTrace();
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLL FAILED {0}", e);
                logger.info("FAILED: "+e.getMessage());
                try { Thread.sleep(backOff); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); return;
                }
                backOff = Math.min(5000,  backOff * 2);
            }
        }
    }
}