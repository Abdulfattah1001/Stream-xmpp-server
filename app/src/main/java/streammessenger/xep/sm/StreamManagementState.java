package streammessenger.xep.sm;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * Holds the complete Stream Management (XEP-0198) state for one session.
 * <p>
 * What Stream Management solves:
 *   Mobile clients frequently lose connectivity (WiFi → 4G, tunnels, etc.)
 *   Without SM: connection drops = all in-flight messages lost forever.
 *   With SM:    server tracks what client has received; on reconnect,
 *               retransmits only the messages that were lost.
 * <p>
 * How it works:
 *   Server sends stanza → adds to unackedQueue with seqNum N
 *   Client receives it  → sends <a h='N'/>
 *   Server removes everything up to N from unackedQueue
 * <p>
 *   Client disconnects  → unackedQueue still has messages
 *   Client reconnects   → sends <resume previd='smId' h='M'/>
 *   Server retransmits  → everything in unackedQueue with seqNum > M
 * <p>
 * Thread safety:
 *   outboundSeq and inboundCount are AtomicLong (lock-free reads).
 *   unackedQueue is guarded by queueLock (read/write lock).
 *   Reads (for retransmission queries) use read lock.
 *   Writes (add/remove from queue) use write lock.
 */
public final class StreamManagementState {

    private static final Logger logger = Logger.getLogger(StreamManagementState.class.getName());

    // Maximum stanzas to hold in the unacked queue.
    // Beyond this, the oldest are dropped (client must reconnect fresh).
    private static final int MAX_UNACKED_QUEUE_SIZE = 1000;

    // SM session ID - used to match a resumption attempt to this state
    private final String smId;

    // Whether this session supports resumption after disconnect
    private final boolean resumable;

    // Wall clock time when SM was enabled - used to expire old SM states
    private final long enabledAt;

    private volatile String lastXml= null;

    // Sequence number for our outbound stanzas
    // Incremented each time we send a stanza tracked by SM
    private final AtomicLong outboundSeq = new AtomicLong(0);

    // Count of stanzas received FROM the client
    // Sent back to client in <a h='N'/> responses
    private final AtomicLong inboundCount = new AtomicLong(0);

    // The highest sequence number the client has confirmed receiving
    private final AtomicLong clientAckedSeq = new AtomicLong(0);

    // Stanzas sent but not yet acknowledged by the client
    // LinkedList for O(1) removal from front (oldest acks first)
    private final LinkedList<UnackedStanza> unackedQueue = new LinkedList<>();
    private final ReentrantReadWriteLock queueLock = new ReentrantReadWriteLock();

    private volatile boolean enabled = true;

    // -------------------------------------------------------------------------

    public StreamManagementState(String smId, boolean resumable) {
        this.smId = smId;
        this.resumable = resumable;
        this.enabledAt = System.currentTimeMillis();
        logger.fine("StreamManagementState created smId=" + smId
                + " resumable=" + resumable);
    }

    // =========================================================================
    // Outbound tracking
    // =========================================================================

    /**
     * Records that a stanza was sent to the client.
     * Adds it to the unacked queue with the next sequence number.
     * <p>
     * Called by Session.writeXML() for every stanza (not SM control frames).
     */
    public void trackOutbound(String xml) {
        long seq = outboundSeq.incrementAndGet();
        UnackedStanza stanza = new UnackedStanza(seq, xml);

        queueLock.writeLock().lock();
        try {
            if (unackedQueue.size() >= MAX_UNACKED_QUEUE_SIZE) {
                // Drop oldest - client is too far behind, resumption won't work anyway
                unackedQueue.poll();
                logger.warning("SM unacked queue full (smId=" + smId + ") - dropping oldest stanza");
            }
            unackedQueue.add(stanza);
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    private String extractAttr(String xml, String attr) {
        String search = attr + "='";
        int s = xml.indexOf(search);
        if (s == -1) {
            search = attr + "=\"";
            s = xml.indexOf(search);
            if (s == -1) return null;
        }
        s += search.length();
        char quote = xml.charAt(s - 1);
        int e = xml.indexOf(quote, s);
        return e == -1 ? null : xml.substring(s, e);
    }

    /**
     * Processes an acknowledgment from the client (<a h='N'/>).
     * Removes all stanzas with seqNum <= h from the unacked queue.
     *
     * @param h The number of stanzas the client claims to have received
     */
    public void processClientAck(long h) {
        if (h < 0 || h > outboundSeq.get()) {
            logger.warning("SM invalid ack h=" + h
                    + " outboundSeq=" + outboundSeq.get()
                    + " smId=" + smId);
            return;
        }

        clientAckedSeq.set(h);

        queueLock.writeLock().lock();
        try {
            // Remove all stanzas that the client confirmed receiving
            unackedQueue.removeIf(s -> {
                if(s.seqNum() == h){

                    lastXml  = s.xml();
                    int memberStart = s.xml().indexOf("<message ");
                    if(memberStart != -1){
                        String eventRefId = extractAttr(s.xml(), "id");
                        logger.info("Ack Message ID is: "+eventRefId);
                    }
                }
                return s.seqNum() <= h;
            });

        } finally {
            queueLock.writeLock().unlock();
        }

        logger.info("SM ack processed h=" + h
                + " remainingUnacked=" + getUnackedCount()
                + " smId=" + smId);
    }

    /**
     * Returns all unacked stanzas with seqNum > h.
     * Used during session resumption to determine what to retransmit.
     *
     * @param h The client's last confirmed sequence number
     * @return List of stanzas to retransmit, in order
     */
    public List<UnackedStanza> getUnackedAfter(long h) {
        queueLock.readLock().lock();
        try {
            List<UnackedStanza> result = new ArrayList<>();
            for (UnackedStanza stanza : unackedQueue) {
                if (stanza.seqNum() > h) {
                    result.add(stanza);
                }
            }
            return result;
        } finally {
            queueLock.readLock().unlock();
        }
    }

    // =========================================================================
    // Inbound tracking
    // =========================================================================

    /**
     * Increments the count of stanzas received from the client.
     * Called by XMPPStreamProcessor on every inbound stanza.
     */
    public void incrementInbound() {
        inboundCount.incrementAndGet();
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getSmId() { return smId; }

    public boolean isResumable() { return resumable; }

    public boolean isEnabled() { return enabled; }

    public void disable() { enabled = false; }

    public long getOutboundSeq() { return outboundSeq.get(); }

    public long getInboundCount() { return inboundCount.get(); }

    public long getClientAcked() { return clientAckedSeq.get(); }

    public long getEnabledAt() { return enabledAt; }

    public int getUnackedCount() {
        queueLock.readLock().lock();
        try {
            return unackedQueue.size();
        } finally {
            queueLock.readLock().unlock();
        }
    }

    public String getLastXml() {
        return lastXml;
    }

    /**
     * Returns all currently unacked stanzas.
     * Used for retransmission during resumption.
     */
    public List<UnackedStanza> getAllUnacked() {
        queueLock.readLock().lock();
        try {
            return new ArrayList<>(unackedQueue);
        } finally {
            queueLock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return String.format(
            "StreamManagementState{smId=%s, resumable=%b, " +
            "outSeq=%d, inCount=%d, clientAcked=%d, unacked=%d}",
            smId, resumable,
            outboundSeq.get(), inboundCount.get(),
            clientAckedSeq.get(), getUnackedCount()
        );
    }
}