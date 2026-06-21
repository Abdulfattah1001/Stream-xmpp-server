package streammessenger.xep.sm;


import java.util.Map;

import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Handles Stream Management (XEP-0198) protocol elements:
 *
 *   <enable>   Client wants to start SM
 *   <enabled>  Server confirms SM is active (we send this)
 *   <r>        Client requests an ack from server
 *   <a>        Client sends an ack to server
 *   <resume>   Client wants to resume a previous SM session
 *   <resumed>  Server confirms resumption (we send this)
 *   <failed>   Resumption failed (we send this)
 *
 * SM session store:
 *   When a client disconnects with an active SM session, the state
 *   (unacked stanzas, sequence numbers) is moved to smStateStore.
 *   It lives there for SM_STATE_TTL_MS before expiring.
 *   On reconnect, the client can resume using the smId.
 *
 * This class is a stateless singleton (handlers map in XMPPStreamProcessor).
 * Per-session state lives in Session.smState and smStateStore.
 */
public final class StreamManagementHandlerOld {

    private static final Logger logger = Logger.getLogger(StreamManagementHandlerOld.class.getName());
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final String SM_NS = "urn:xmpp:sm:3";

    // How long to keep SM state after disconnect (5 minutes)
    private static final long SM_STATE_TTL_MS = 5 * 60 * 1000;

    // Stores SM state after session disconnects, keyed by smId
    // Allows resumption within TTL window
    private final Map<String, PersistedSmState> smStateStore = new ConcurrentHashMap<>();

    private final SessionRegistry registry;

    // Background task to expire old SM states
    private final ScheduledExecutorService smStateCleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sm-state-cleaner");
                t.setDaemon(true);
                return t;
            });

    public StreamManagementHandlerOld(SessionRegistry registry) {
        this.registry = registry;
        // Clean up expired SM states every 2 minutes
        smStateCleaner.scheduleAtFixedRate(
                this::cleanExpiredStates, 2, 2, TimeUnit.MINUTES);
    }

    // =========================================================================
    // Protocol handlers
    // =========================================================================

    /**
     * Client sends <enable xmlns='urn:xmpp:sm:3' resume='true'/>
     * Enables Stream Management for this session.
     *
     * Must only be called after authentication (XEP-0198 §3).
     */
    public void handleEnable(StartElement element, Session session) {
        if (!session.isAuthenticated()) {
            session.writeXML(
                    "<failed xmlns='" + SM_NS + "'>" +
                            "<not-authorized xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                            "</failed>"
            );
            return;
        }

        if (session.hasStreamManagement()) {
            // SM already enabled on this session
            session.writeXML(
                    "<failed xmlns='" + SM_NS + "'>" +
                            "<unexpected-request xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                            "</failed>"
            );
            return;
        }

        // Parse resume attribute
        Attribute resumeAttr = element.getAttributeByName(new QName("resume"));
        boolean resumable = resumeAttr != null &&
                "true".equalsIgnoreCase(resumeAttr.getValue());

        String smId = generateSmId();
        session.enableStreamManagement(smId, resumable);

        session.writeXML(String.format(
                "<enabled xmlns='%s' id='%s' resume='%s'/>",
                SM_NS, smId, resumable
        ));

        logger.info("SM enabled uid=" + session.getSessionId()
                + " smId=" + smId + " resumable=" + resumable);
    }

    /**
     * Client sends <r xmlns='urn:xmpp:sm:3'/>
     * Client is requesting an acknowledgment from us.
     * We respond with <a h='N'/> where N = inbound stanza count.
     */
    public void handleRequestAck(Session session) {
        if (!session.hasStreamManagement()) {
            return; // Ignore if SM not enabled
        }

        session.writeXML(String.format(
                "<a xmlns='%s' h='%d'/>",
                SM_NS, session.getInboundCount()
        ));
    }

    /**
     * Client sends <a xmlns='urn:xmpp:sm:3' h='N'/>
     * Client is acknowledging receipt of N stanzas from us.
     */
    public void handleAck(StartElement element, Session session) {
        if (!session.hasStreamManagement()) {
            return;
        }

        Attribute hAttr = element.getAttributeByName(new QName("h"));
        if (hAttr == null) {
            logger.warning("SM <a> missing h attribute from uid=" + session.getSessionId());
            return;
        }

        try {
            long h = Long.parseLong(hAttr.getValue());
            session.processAck(h);
        } catch (NumberFormatException e) {
            logger.warning("SM <a> invalid h value from uid=" + session.getSessionId()
                    + ": " + hAttr.getValue());
        }
    }

    /**
     * Client sends <resume xmlns='urn:xmpp:sm:3' previd='smId' h='N'/>
     * Client wants to resume a previous session after disconnect.
     *
     * h = number of stanzas the client received before disconnect.
     * We retransmit everything we sent that the client didn't confirm.
     */
    public void handleResume(StartElement element, XMLEventReader reader, Session newSession) {
        Attribute prevIdAttr = element.getAttributeByName(new QName("previd"));
        Attribute hAttr = element.getAttributeByName(new QName("h"));

        if (prevIdAttr == null || hAttr == null) {
            sendResumeFailed(newSession, "bad-request");
            return;
        }

        String prevSmId = prevIdAttr.getValue();
        long h;
        try {
            h = Long.parseLong(hAttr.getValue());
        } catch (NumberFormatException e) {
            sendResumeFailed(newSession, "bad-request");
            return;
        }

        // Look up the persisted SM state
        PersistedSmState persisted = smStateStore.remove(prevSmId);
        if (persisted == null) {
            logger.info("SM resume failed: state not found smId=" + prevSmId);
            sendResumeFailed(newSession, "item-not-found");
            return;
        }

        // Check TTL
        if (System.currentTimeMillis() - persisted.savedAt() > SM_STATE_TTL_MS) {
            logger.info("SM resume failed: state expired smId=" + prevSmId);
            sendResumeFailed(newSession, "item-not-found");
            return;
        }

        StreamManagementState smState = persisted.state();

        // Re-enable SM on the new session with the existing state
        newSession.enableStreamManagement(prevSmId, smState.isResumable());
        // Transfer the sequence state
        smState.processClientAck(h); // Remove what client confirmed before disconnect

        // Confirm resumption - tell client how many we received from them
        newSession.writeXML(String.format(
                "<resumed xmlns='%s' h='%d' previd='%s'/>",
                SM_NS, smState.getInboundCount(), prevSmId
        ));

        // Retransmit everything client didn't confirm
        List<UnackedStanza> toRetransmit = smState.getUnackedAfter(h);
        logger.info("SM resuming smId=" + prevSmId
                + " retransmitting=" + toRetransmit.size() + " stanzas");

        for (UnackedStanza stanza : toRetransmit) {
            newSession.writeXML(stanza.xml());
        }
    }

    /**
     * Called when a session with SM enabled disconnects.
     * Saves the SM state to smStateStore so it can be resumed.
     * If not resumable, state is discarded.
     */
    public void onSessionDisconnect(Session session) {
        if (!session.hasStreamManagement()) return;

        StreamManagementState smState = session.getSmState();
        if (!smState.isResumable()) {
            smState.disable();
            return;
        }

        // Save state for potential resumption
        smStateStore.put(smState.getSmId(),
                new PersistedSmState(smState, System.currentTimeMillis()));

        logger.info("SM state saved for resumption smId=" + smState.getSmId()
                + " unacked=" + smState.getUnackedCount());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void sendResumeFailed(Session session, String condition) {
        session.writeXML(String.format(
                "<failed xmlns='%s'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                        "</failed>",
                SM_NS, condition
        ));
    }

    private void cleanExpiredStates() {
        long now = System.currentTimeMillis();
        int removed = 0;

        for (Map.Entry<String, PersistedSmState> entry : smStateStore.entrySet()) {
            if (now - entry.getValue().savedAt() > SM_STATE_TTL_MS) {
                smStateStore.remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            logger.info("SM state cleaner removed " + removed + " expired states. "
                    + "Remaining: " + smStateStore.size());
        }
    }

    /**
     * Generates a cryptographically random SM session ID.
     * Must be unguessable to prevent session hijacking.
     */
    private String generateSmId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("sm-");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void shutdown() {
        smStateCleaner.shutdown();
        try {
            if (!smStateCleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                smStateCleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            smStateCleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Wraps an SM state with the timestamp it was saved at.
     * Used to enforce TTL expiration.
     */
    private record PersistedSmState(StreamManagementState state, long savedAt) {}
}