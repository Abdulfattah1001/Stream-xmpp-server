package streammessenger.xep.sm;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.session.SessionState;

/**
 * Handles all Stream Management (XEP-0198) protocol elements.
 * <p>
 * WHY SESSION RESUMPTION MATTERS FOR MOBILE:
 * ─────────────────────────────────────────── </p>
 * Mobile networks are unreliable. A user might:
 *   - Walk into an elevator (signal lost for 30 seconds)
 *   - Switch from WiFi to 4G (brief disconnect)
 *   - Phone screen turns off (OS may kill TCP connection)
 *   - Travel through a tunnel
 * <p>
 * Without resumption: every disconnect = full re-auth
 *   = STARTTLS + SASL + bind = 3-5 round trips = 1-3 seconds
 *   = User sees "Connecting..." every time
 *   = Messages sent during disconnect potentially lost
 * <p>
 * With resumption: disconnect = 1 round trip
 *   = TLS + <resume/> + <resumed/> = ~100ms
 *   = Transparent to user
 *   = ALL missed messages delivered in correct order
 *   = NO duplicate messages
 * <p>
 * THE CRITICAL STATE: smStateStore
 * ───────────────────────────────── </p>
 * When a session disconnects with active SM:
 *   - The StreamManagementState is MOVED to smStateStore
 *   - It lives there for SM_STATE_TTL (5 minutes by default)
 *   - The client has the smId (from when it sent <enable/>)
 *   - Within TTL: client sends <resume previd='smId' h='N'/> → fast path
 *   - After TTL: state is gone → client must do full auth
 * <p>
 * The 5-minute TTL covers:
 *   - Brief network blips (seconds)
 *   - Elevator/tunnel scenarios (1-2 minutes)
 *   - App backgrounding by OS (minutes)
 *   It does NOT cover:
 *   - App being force-killed and restarted after hours
 *   - Phone being turned off overnight
 *   (Those require full re-auth which is fine - token is stored on device)
 */
public final class StreamManagementHandler {

    private static final Logger logger =
            Logger.getLogger(StreamManagementHandler.class.getName());

    private static final String SM_NS = "urn:xmpp:sm:3";

    /**
     * How long to keep SM state after disconnect.
     * Client has this window to reconnect and resume.
     * After this: state is gone, client must do full auth.
     * <p>
     * 5 minutes covers most mobile network scenarios.
     * Increase to 10+ minutes for flakier networks.
     */
    private static final long SM_STATE_TTL_MS = 5 * 60 * 1000L;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Stores SM state after session disconnects.
     * Key: smId
     * Value: PersistedSmState (state + savedAt timestamp)
     * <p>
     * Thread safe: ConcurrentHashMap
     */
    private final ConcurrentHashMap<String, PersistedSmState> smStateStore =
            new ConcurrentHashMap<>();

    private final SessionRegistry registry;

    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sm-state-cleaner");
                t.setDaemon(true);
                return t;
            });

    public StreamManagementHandler(SessionRegistry registry) {
        this.registry = registry;
        // Clean expired SM states every 2 minutes
        cleaner.scheduleAtFixedRate(
                this::cleanExpiredStates, 2, 2, TimeUnit.MINUTES);
    }

    // =========================================================================
    // Protocol handlers
    // =========================================================================

    /**
     * Client sends <enable xmlns='urn:xmpp:sm:3' resume='true'/>
     * <p>
     * Must be called AFTER authentication and resource binding.
     * Creates a new SM state and attaches it to the session.
     * Responds with <enabled id='smId' resume='true'/>.
     * <p>
     * The smId is the client's key for future resumption.
     * Client stores it alongside the session token.
     */
    public void handleEnable(StartElement element, Session session) {
        if (!session.isAuthenticated()) { //The user is not authenticated
            session.writeXML(buildFailed("not-authorized"));
            return;
        }

        if (session.hasStreamManagement()) { //The Stream management has been enabled before
            session.writeXML(buildFailed("unexpected-request"));
            return;
        }

        Attribute resumeAttr = element.getAttributeByName(new QName("resume"));
        boolean resumable = resumeAttr == null
                || "true".equalsIgnoreCase(resumeAttr.getValue());


        String smId = generateSmId();
        session.enableStreamManagement(smId, resumable);
        logger.info("The stream id generated is: "+smId);

        session.writeXML(String.format(
            "<enabled xmlns='%s' id='%s' resume='%s'/>",
            SM_NS, smId, resumable
        ));

        logger.info("SM enabled uid=" + session.getSessionId()
                + " smId=" + smId + " resumable=" + resumable);
    }

    /**
     * Client sends <r xmlns='urn:xmpp:sm:3'/>
     * <p>
     * Client wants an ack of how many stanzas we received from it.
     * We respond: <a h='N'/> where N = our inbound count.
     * <p>
     * This is the server-side ack of client stanzas.
     * Symmetric to the client acking server stanzas with <a h='N'/>.
     */
    public void handleRequestAck(Session session) {
        logger.info("Handling client <r> tags");
        if (!session.hasStreamManagement()) return;

        session.writeXML(String.format(
            "<a xmlns='%s' h='%d'/>",
            SM_NS, session.getInboundCount()
        ));
    }

    /**
     * Client sends <a xmlns='urn:xmpp:sm:3' h='N'/>
     * <p>
     * Client is acknowledging it received N stanzas from us.
     * We remove everything ≤ N from our unacked queue.
     * Those stanzas will NOT be retransmitted on reconnect.
     */
    public void handleAck(StartElement element, Session session) {
        if (!session.hasStreamManagement()) return;

        Attribute hAttr = element.getAttributeByName(new QName("h"));
        if (hAttr == null) {
            logger.warning("SM <a> missing h from uid=" + session.getSessionId());
            return;
        }

        try {
            long h = Long.parseLong(hAttr.getValue().trim());
            logger.info("Processing  ack from client");
            session.processAck(h);

            logger.fine("SM ack: uid=" + session.getSessionId()
                    + " h=" + h
                    + " remaining=" + session.getSmState().getUnackedCount());

        } catch (NumberFormatException e) {
            logger.warning("SM <a> invalid h: " + hAttr.getValue());
        }
    }

    /**
     * Client sends <resume xmlns='urn:xmpp:sm:3' previd='smId' h='N'/>
     * <p>
     * This is the FAST RECONNECT PATH.
     * No SASL auth, no resource binding, just resume.
     * <p>
     * h = how many stanzas the client received from us before disconnect.
     * We retransmit everything after h.
     * <p>
     * Called DURING the TLS stream, before any SASL features are sent.
     * If successful: session is immediately AUTHENTICATED and ready.
     * If failed: client must do full SASL authentication.
     *
     * @return true if resumption succeeded, false if client must re-auth
     */
    public boolean handleResume(StartElement element,
                                 XMLEventReader reader,
                                 Session newSession) {
        Attribute prevIdAttr = element.getAttributeByName(
                new QName("previd"));
        Attribute hAttr = element.getAttributeByName(new QName("h"));

        ///Armed robber state; reject outright
        if (prevIdAttr == null || hAttr == null) {
            newSession.writeXML(buildFailed("bad-request"));
            return false;
        }

        String prevSmId = prevIdAttr.getValue().trim();
        long h; //The last ack stanza received by the client
        try {
            h = Long.parseLong(hAttr.getValue().trim());
        } catch (NumberFormatException e) {
            newSession.writeXML(buildFailed("bad-request"));
            return false;
        }

        // Look up the persisted state
        PersistedSmState persisted = smStateStore.remove(prevSmId);

        if (persisted == null) {
            logger.info("SM resume failed: state not found smId=" + prevSmId + " uid=" + newSession.getSessionId());
            newSession.writeXML(buildFailed("item-not-found"));
            return false;
        }

        // Check TTL
        if (System.currentTimeMillis() - persisted.savedAt() > SM_STATE_TTL_MS) {
            logger.info("SM resume failed: state expired smId=" + prevSmId);
            newSession.writeXML(buildFailed("item-not-found"));
            return false;
        }

        StreamManagementState smState = persisted.state();

        // Validate h is same
        if (h < 0 || h > smState.getOutboundSeq()) {
            logger.warning("SM resume invalid h=" + h
                    + " outboundSeq=" + smState.getOutboundSeq());
            newSession.writeXML(buildFailed("bad-request"));
            return false;
        }

        // =====================================================================
        // RESUMPTION SUCCEEDS
        // =====================================================================

        // 1. Remove stanzas the client confirmed receiving
        //    These will NOT be retransmitted - client already has them
        smState.processClientAck(h);

        // 2. Re-enable SM on new session with the EXISTING state
        //    (not a new state - we're continuing the old one)
        newSession.enableStreamManagementWithState(smState);

        // 3. Restore session identity from the persisted contact info
        newSession.setContactId(persisted.contactId());
        newSession.setResource(persisted.resource());
        newSession.setSessionState(SessionState.AUTHENTICATED);

        // 4. Re-register in registry (new uid, same contactId)
        registry.bindAuthenticatedSession(persisted.contactId(), newSession);

        // 5. Confirm resumption to client
        //    h here = our inbound count (how many we received from client)
        newSession.writeXML(String.format(
            "<resumed xmlns='%s' h='%d' previd='%s'/>",
            SM_NS, smState.getInboundCount(), prevSmId
        ));

        // 6. Retransmit everything client didn't confirm receiving
        //    These are stanzas with seqNum > h
        List<UnackedStanza> toRetransmit = smState.getUnackedAfter(h);

        logger.info(String.format(
                "SM resuming: smId=%s retransmitting=%d stanzas after h=%d",
                prevSmId, toRetransmit.size(), h
        ));

        for (UnackedStanza stanza : toRetransmit) {
            newSession.writeXML(stanza.xml());
        }

        logger.info(String.format(
                "SM resumed: uid=%s contactId=%s smId=%s retransmitted=%d",
                newSession.getSessionId(),
                persisted.contactId(),
                prevSmId,
                toRetransmit.size()
        ));

        return true;
    }

    /**
     * Called when a session with active SM disconnects.
     * <p>
     * Moves the SM state to smStateStore so the client can resume.
     * If SM is not resumable: state is discarded immediately.
     * <p>
     * Called by ConnectionHandler.cleanup() before removing the session
     * from the registry.
     */
    public void onSessionDisconnect(Session session) {
        logger.info("A session has just disconnected");
        if (!session.hasStreamManagement()) return;

        StreamManagementState smState = session.getSmState();

        if (!smState.isResumable()) {
            smState.disable();
            logger.info("SM state discarded (not resumable) uid="
                    + session.getSessionId());
            return;
        }

        // Save state for potential resumption
        smStateStore.put(smState.getSmId(), new PersistedSmState(
                smState,
                session.getContactId(),
                session.getResource(),
                System.currentTimeMillis()
        ));

        logger.info(String.format(
                "SM state saved: smId=%s uid=%s contactId=%s " +
                        "unacked=%d ttlMs=%d",
                smState.getSmId(),
                session.getSessionId(),
                session.getContactId(),
                smState.getUnackedCount(),
                SM_STATE_TTL_MS
        ));
    }

    // =========================================================================
    // State store management
    // =========================================================================

    /**
     * Removes expired SM states from the store.
     * Runs every 2 minutes as a background task.
     * <p>
     * After TTL expires, the client MUST do full re-authentication.
     * The session token stored on the device handles this transparently.
     */
    private void cleanExpiredStates() {
        long now = System.currentTimeMillis();
        int removed = 0;

        for (Map.Entry<String, PersistedSmState> entry
                : smStateStore.entrySet()) {
            if (now - entry.getValue().savedAt() > SM_STATE_TTL_MS) {
                smStateStore.remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0 || !smStateStore.isEmpty()) {
            logger.info(String.format(
                    "SM state cleaner: removed=%d remaining=%d",
                    removed, smStateStore.size()
            ));
        }
    }

    /**
     * Returns the number of SM states currently persisted.
     * Useful for monitoring.
     */
    public int getPersistedStateCount() {
        return smStateStore.size();
    }

    public void shutdown() {
        cleaner.shutdown();
        try {
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        smStateStore.clear();
        logger.info("SM handler shutdown. States cleared.");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Generates a cryptographically random SM session ID.
     * <p>
     * Format: sm-<32 hex chars>
     * Entropy: 128 bits
     * <p>
     * Must be unguessable - an attacker with a valid smId could
     * potentially hijack a session resumption.
     */
    private String generateSmId() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("sm-");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String buildFailed(String condition) {
        return String.format(
                "<failed xmlns='%s'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                        "</failed>",
                SM_NS, condition
        );
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Wraps SM state with everything needed to restore a session.
     * <p>
     * contactId and resource are stored separately because the Session
     * object itself is gone - only the SM state is preserved.
     */
    private record PersistedSmState(
            StreamManagementState state,
            String contactId,
            String resource,
            long savedAt
    ) {}
}