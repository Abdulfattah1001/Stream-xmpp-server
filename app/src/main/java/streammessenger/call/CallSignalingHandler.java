package streammessenger.call;


import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VideoGrant;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.config.ServerConfig;
import streammessenger.db.ConnectionPool;
import streammessenger.push.PushNotificationService;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.stanza.StanzaHandler;

/**
 * Voice and Video Call Signaling via WebRTC Or Twilio.
 * <p>
 * Custom namespace: urn:xmpp:call:0
 * <p>
 * HOW WEBRTC CALLS WORK:
 * ────────────────────────
 * WebRTC handles the actual audio/video transmission peer-to-peer.
 * Our XMPP server only handles the SIGNALING (setup/teardown).
 * <p>
 * The signaling flow:
 * <p>
 *   Alice                  Server                  Bob
 *     │                       │                      │
 *     │── initiate call ─────▶│                      │
 *     │   (SDP offer)         │─ push notification ─▶│
 *     │                       │   + XMPP invite      │
 *     │                       │                      │
 *     │                       │◀── answer (accept) ──│
 *     │◀── answer relayed ────│                      │
 *     │                       │                      │
 *     │◀══════════════════════ ICE candidates ═══════│
 *     │══════════════════════ exchanged via server ═▶│
 *     │                       │                      │
 *     │◀══════════════════════ P2P audio/video ══════│
 *     │   (direct after ICE)  │   (no server relay)  │
 * <p>
 * OPERATIONS:
 * ───────────
 *   initiate  → Caller sends SDP offer
 *   answer    → Callee sends SDP answer (accept)
 *   decline   → Callee rejects the call
 *   ice       → Exchange ICE candidates (network path discovery)
 *   end       → Either party hangs up
 *   busy      → Callee is in another call
 * <p>
 * CALL TYPES:
 *   voice → Audio only
 *   video → Audio + Video
 * <p>
 * STUN/TURN:
 *   STUN: Helps devices discover their public IP
 *         Use Google's free STUN: stun:stun.l.google.com:19302
 *   TURN: Relay server for when P2P fails (firewalls)
 *         Self-host: coturn (open source, free)
 *         docker run -p 3478:3478 coturn/coturn
 * <p>
 * Example - Initiate call:
 *   <iq type='set' id='c1'>
 *     <call xmlns='urn:xmpp:call:0' action='initiate' type='video'>
 *       <to>u_bob@domain.com</to>
 *       <call_id>uuid</call_id>
 *       <sdp>v=0
 *   o=- 123 2 IN IP4 127.0.0.1
 *   s=-
 *   ...SDP offer content...</sdp>
 *     </call>
 *   </iq>
 * <p>
 * Example - ICE candidate:
 *   <iq type='set' id='c2'>
 *     <call xmlns='urn:xmpp:call:0' action='ice'>
 *       <call_id>uuid</call_id>
 *       <candidate>candidate:1 1 UDP 2130706431 192.168.1.1 8888
 *                  typ host</candidate>
 *     </call>
 *   </iq>
 */
public final class CallSignalingHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(CallSignalingHandler.class.getName());

    private static final String CALL_NS = "urn:xmpp:call:0";

    // How long a call can ring before auto-declining (60 seconds)
    private static final long RING_TIMEOUT_MS = 60_000;

    // Active call state: callId → CallState
    private final ConcurrentHashMap<String, CallState> activeCalls =
            new ConcurrentHashMap<>();

    // Ring timeout tasks: callId → future
    private final ConcurrentHashMap<String, ScheduledFuture<?>> ringTimeouts =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "call-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ConnectionPool pool;
    private final SessionRegistry registry;
    private final PushNotificationService pushService;

    private final ServerConfig config;

    public CallSignalingHandler(ConnectionPool pool,
                                SessionRegistry registry,
                                PushNotificationService pushService, ServerConfig config) {
        this.pool        = pool;
        this.registry    = registry;
        this.pushService = pushService;
        this.config = config;
    }

    @Override
    public void handle(StartElement element,
                        XMLEventReader reader,
                        Session session) {
        if (!session.isAuthenticated()) {
            consumeElement(reader);
            return;
        }


        String iqId = getAttr(element, "id");

        CallRequest req = parseRequest(reader);

        if (req == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (req.action()) {
            case "initiate" -> handleInitiate(req, iqId, session);
            case "answer"   -> handleAnswer(req, iqId, session);
            case "decline"  -> handleDecline(req, iqId, session);
            case "ice"      -> handleIceCandidate(req, iqId, session);
            case "end"      -> handleEnd(req, iqId, session);
            case "busy"     -> handleBusy(req, iqId, session);
            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Initiate Call
    // =========================================================================

    /**
     * Caller sends SDP offer to initiate a call.
     * <p>
     * Actions:
     *  1. Validate neither party is in another call
     *  2. Store call record in DB
     *  3. Route SDP offer to callee
     *  4. Send push notification to callee (wake up the app)
     *  5. Set ring timeout (60s - auto decline if no answer)
     */
    private void handleInitiate(CallRequest req,
                                 String iqId,
                                 Session callerSession) {

        if (req.to() == null || req.sdp() == null || req.callId() == null) {
            sendError(callerSession, iqId, "bad-request");
            return;
        }

        String callType = req.callType() != null ? req.callType() : "voice";
        String callerId = extractUserId(callerSession.getContactId());
        String calleeJid = toBareJid(req.to());
        String calleeId  = extractUserId(calleeJid);
        String callId = UUID.randomUUID().toString();
        String roomName = "call-"+callId;

        // Check caller not already in a call
        if (isInActiveCall(callerId)) {
            logger.info("Already in a call...");
            callerSession.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                "<call xmlns='%s' action='busy'>" +
                "<reason>You are already in a call</reason>" +
                "</call></iq>",
                escapeXml(iqId), CALL_NS
            ));
            return;
        }

        // Store call state, if the caller is not already in a call
        CallState state = new CallState(
                req.callId(), callerId,
                callerSession.getContactId(),
                calleeId, calleeJid,
                callType, System.currentTimeMillis());

        // Put it in a memory for fast look-up
        activeCalls.put(req.callId(), state);

        // Create DB record
        createCallRecord(req.callId(), callerId, calleeId, callType);

        // Acknowledge to caller
        // The callee device is ringing
        callerSession.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<call xmlns='%s' action='ringing'>" +
            "<call_id>%s</call_id>" +
             "<twilio_room>%s</twilio_room>" +
            "</call></iq>",
            escapeXml(iqId), CALL_NS,
            req.callId(), roomName
        ));

        // Route SDP offer to callee
        String offerStanza = String.format(
            "<iq type='set' from='%s' to='%s'>" +
            "<call xmlns='%s' action='incoming' type='%s'>" +
            "<call_id>%s</call_id>" +
            "<from_jid>%s</from_jid>" +
            "<sdp>%s</sdp>" +
            "</call></iq>",
            escapeXml(callerSession.getContactId()),
            escapeXml(calleeJid),
            CALL_NS, escapeXml(callType),
            escapeXml(req.callId()),
            escapeXml(callerSession.getContactId()),
            escapeXml(req.sdp())
        );

        boolean calleeOnline = registry.getByContactId(calleeJid)
                .filter(Session::isAuthenticated)
                .map(s -> s.writeXML(offerStanza))
                .orElse(false);

        // Always send push notification for calls (wakes up the app if the app is in the background state)
        String callerName = getDisplayName(callerId);

        pushService.sendPushCallNotification(calleeId, callerName, callType, req.callId());

        if (!calleeOnline) {
            logger.info("Callee offline for call " + req.callId() + " - push notification sent");
        }

        // Schedule ring timeout - auto-decline after 60 seconds
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            CallState cs = activeCalls.remove(req.callId());
            if (cs != null && "ringing".equals(cs.state())) {
                // Notify caller of missed call
                registry.getByContactId(callerSession.getContactId())
                        .filter(Session::isAuthenticated)
                        .ifPresent(s -> s.writeXML(String.format(
                            "<message from='%s'>" +
                            "<call xmlns='%s' action='missed'>" +
                            "<call_id>%s</call_id>" +
                            "</call></message>",
                            escapeXml(calleeJid),
                            CALL_NS, req.callId()
                        )));

                // Update DB to missed
                updateCallState(req.callId(), "missed", null);
                logger.info("Call missed (timeout): " + req.callId());

                //TODO: Also notify the callee of missed call if they are online else cache it as a message stanza
                Optional<Session> recipientSession = registry.getByContactId(calleeId);

                boolean delivered = false;
                if(recipientSession.isPresent() && recipientSession.get().isAuthenticated()){
                    delivered = recipientSession.get().writeXML(String.format(
                            "<message from='%s'>" +
                                    "<call xmlns='%s' action='missed'>" +
                                    "<call_id>%s</call_id>" +
                                    "</call></message>",
                            escapeXml(calleeId),
                            CALL_NS, req.callId()
                    ));

                    if(delivered){
                        //TODO: Updates metrics here
                    }else{
                        //TODO: Persist the message till the callee comes online
                        logger.info("Persisting the call for when the user logs-in");
                    }
                }
            }
        }, RING_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        ringTimeouts.put(req.callId(), timeout);

        logger.info("Call initiated: callId=" + req.callId()
                + " type=" + callType
                + " caller=" + callerSession.getContactId()
                + " callee=" + calleeJid);
    }

    // =========================================================================
    // Answer (Accept)
    // =========================================================================

    /**
     * Callee accepts the call and sends their SDP answer.
     * <p>
     * Actions:
     *  1. Cancel ring timeout
     *  2. Update call state to answered
     *  3. Route SDP answer back to caller
     *  4. Both sides now have SDP offer+answer → ICE negotiation begins
     */
    private void handleAnswer(CallRequest req,
                               String iqId,
                               Session calleeSession) {

        if (req.callId() == null || req.sdp() == null) {
            sendError(calleeSession, iqId, "bad-request");
            return;
        }

        CallState state = activeCalls.get(req.callId());

        if (state == null) {
            sendError(calleeSession, iqId, "item-not-found");
            return;
        }

        // Cancel ring timeout
        ScheduledFuture<?> timeout = ringTimeouts.remove(req.callId());

        if (timeout != null) timeout.cancel(false);

        // Update state
        activeCalls.put(req.callId(), state.withState("active"));

        updateCallState(req.callId(), "answered", System.currentTimeMillis());

        logger.info("The caller ID is: " + state.callerId() + "with JID " + state.callerJid());
        logger.info("The caller ID is: " + state.calleeId() + "with JID " + state.calleeJid());

        String callerToken = twilioTokenGenerator(state.callerId(), req.callId());
        String calleeToken = twilioTokenGenerator(calleeSession.getUid(), req.callId());

        String roomName = videoRoomNameGenerator();
        // Acknowledge to callee with the twilio token still using the iqId of the answer action
        calleeSession.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<call xmlns='%s' action='answered'>" +
            "<call_id>%s</call_id>" +
             "<room_name> %s </room_name>" +
             "<token> %s </token>" +
            "</call></iq>",
            escapeXml(iqId), CALL_NS, req.callId(), roomName, calleeToken
        ));

        //Acknowledge to the caller with the twilio token
        Optional<Session> caller = registry.getByUserId(state.callerId());

        if(caller.isEmpty()) {
            logger.info("Caller is absent");
        }else {
            logger.info("Caller is present, routing the answer to him ");
            // Route SDP answer to caller
            String answerStanza = String.format(
                    "<iq type='set' from='%s' to='%s'>" +
                            "<call xmlns='%s' action='answer'>" +
                            "<call_id>%s</call_id>" +
                            "<sdp>%s</sdp>" +
                            "<room_name> %s </room_name>" +
                            "<token> %s </token>" +
                            "</call></iq>",
                    escapeXml(calleeSession.getContactId()),
                    escapeXml(state.callerJid()),
                    CALL_NS,
                    escapeXml(req.callId()),
                    escapeXml(req.sdp()),
                    escapeXml(roomName),
                    escapeXml(callerToken)
            );
            caller.get().writeXML(answerStanza);
        }

        /*registry.getByUserId(state.callerId())
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(answerStanza));*/

        logger.info("Call answered: callId=" + req.callId());
    }

    // =========================================================================
    // Decline
    // =========================================================================

    /**
     * Callee declines the incoming call.
     */
    private void handleDecline(CallRequest req,
                                String iqId,
                                Session calleeSession) {
        logger.info("Handling declining calls ... ");

        if (req.callId() == null) {
            sendError(calleeSession, iqId, "bad-request");
            return;
        }

        CallState state = activeCalls.remove(req.callId());
        if (state == null) {
            sendError(calleeSession, iqId, "item-not-found");
            return;
        }

        // Cancel ring timeout
        ScheduledFuture<?> timeout = ringTimeouts.remove(req.callId());
        if (timeout != null) timeout.cancel(false);

        // Update DB
        updateCallState(req.callId(), "declined", null);

        // Acknowledge
        calleeSession.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // Notify caller
        /*registry.getByContactId(state.callerJid())
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(String.format(
                    "<message from='%s'>" +
                    "<call xmlns='%s' action='declined'>" +
                    "<call_id>%s</call_id>" +
                    "</call></message>",
                    escapeXml(calleeSession.getContactId()),
                    CALL_NS, req.callId()
                )));*/

        Optional<Session> caller = registry.getByUserId(state.callerId());
        caller.ifPresent(session -> session.writeXML(String.format(
                "<message from='%s' id='%s' to='%s'>" +
                        "<call xmlns='%s' action='declined'>" +
                        "<call_id>%s</call_id>" +
                        "</call></message>",
                escapeXml(calleeSession.getContactId()),
                UUID.randomUUID().toString(),
                session.getUid(),
                CALL_NS, req.callId()
        )));

        logger.info("Call declined: callId=" + req.callId());
    }

    private String videoRoomNameGenerator() {
        return UUID.randomUUID().toString();
    }

    // =========================================================================
    // ICE Candidate Exchange
    // =========================================================================

    /**
     * Relays ICE candidates between caller and callee.
     * <p>
     * ICE candidates are network path options WebRTC uses to establish
     * the best P2P connection (local network, STUN, or TURN fallback).
     * <p>
     * Both sides send candidates simultaneously as they're discovered.
     * Once both sides have matching candidates, the P2P connection forms.
     */
    private void handleIceCandidate(CallRequest req,
                                     String iqId,
                                     Session senderSession) {

        if (req.callId() == null || req.iceCandidate() == null) {
            sendError(senderSession, iqId, "bad-request");
            return;
        }

        CallState state = activeCalls.get(req.callId());
        if (state == null) {
            // Call may have already ended - silently ignore
            senderSession.writeXML(String.format(
                "<iq type='result' id='%s'/>", escapeXml(iqId)));
            return;
        }

        // Route to the other party
        String senderJid    = senderSession.getContactId();
        String recipientJid = state.callerJid().equals(senderJid)
                ? state.calleeJid()
                : state.callerJid();

        String candidateStanza = String.format(
            "<iq type='set' from='%s' to='%s'>" +
            "<call xmlns='%s' action='ice'>" +
            "<call_id>%s</call_id>" +
            "<candidate>%s</candidate>" +
            "</call></iq>",
            escapeXml(senderJid),
            escapeXml(recipientJid),
            CALL_NS,
            escapeXml(req.callId()),
            escapeXml(req.iceCandidate())
        );

        registry.getByContactId(recipientJid)
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(candidateStanza));

        // Acknowledge
        senderSession.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));
    }

    // =========================================================================
    // End Call
    // =========================================================================

    /**
     * Either party hangs up.
     * Notifies the other party and records call duration.
     */
    private void handleEnd(CallRequest req,
                            String iqId,
                            Session senderSession) {

        logger.info("Handling calls ended event");

        if (req.callId() == null) {
            sendError(senderSession, iqId, "bad-request");
            return;
        }

        CallState state = activeCalls.remove(req.callId());

        // Cancel any pending ring timeout
        ScheduledFuture<?> timeout = ringTimeouts.remove(req.callId());
        if (timeout != null) timeout.cancel(false);

        // Calculate duration
        long durationMs = state != null
                ? System.currentTimeMillis() - state.startedAt()
                : 0;
        int durationSeconds = (int) (durationMs / 1000);

        logger.info("Calls duratioin is: "+durationSeconds);

        // Update DB
        updateCallEnded(req.callId(), durationSeconds);

        // Acknowledge to sender
        senderSession.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<call xmlns='%s' action='ended'>" +
            "<call_id>%s</call_id>" +
            "<duration>%d</duration>" +
            "</call></iq>",
            escapeXml(iqId), CALL_NS,
            req.callId(), durationSeconds
        ));

        // Notify the other party
        if (state != null) {
            String senderJid    = senderSession.getUid();
            String recipientJid = state.callerJid().equals(senderJid)
                    ? state.calleeJid()
                    : state.callerJid();

            String endStanza = String.format(
                "<message from='%s'>" +
                "<call xmlns='%s' action='ended'>" +
                "<call_id>%s</call_id>" +
                "<duration>%d</duration>" +
                "</call></message>",
                escapeXml(senderJid),
                CALL_NS,
                req.callId(), durationSeconds
            );

            registry.getByContactId(recipientJid)
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(endStanza));
        }

        logger.info("Call ended: callId=" + req.callId()
                + " duration=" + durationSeconds + "s");
    }

    // =========================================================================
    // Busy
    // =========================================================================

    /**
     * Callee is already in a call.
     */
    private void handleBusy(CallRequest req,
                             String iqId,
                             Session calleeSession) {

        logger.info("Handling busy call events");
        if (req.callId() == null) {
            sendError(calleeSession, iqId, "bad-request");
            return;
        }

        CallState state = activeCalls.remove(req.callId());
        if (state == null) {
            sendError(calleeSession, iqId, "item-not-found");
            return;
        }

        ScheduledFuture<?> timeout = ringTimeouts.remove(req.callId());
        if (timeout != null) timeout.cancel(false);

        updateCallState(req.callId(), "busy", null);

        calleeSession.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // Notify caller
        registry.getByContactId(state.callerJid())
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(String.format(
                    "<message from='%s'>" +
                    "<call xmlns='%s' action='busy'>" +
                    "<call_id>%s</call_id>" +
                    "</call></message>",
                    escapeXml(calleeSession.getContactId()),
                    CALL_NS, req.callId()
                )));
    }

    private String twilioTokenGenerator(String uid, String name) {


        // Required for Video
        String identity = uid;

        // Create Video grant
        VideoGrant grant = new VideoGrant().setRoom(name);

        // Create access token
        AccessToken token = new AccessToken.Builder(
                twilioAccountSid,
                twilioApiKey,
                twilioApiSecret.getBytes(StandardCharsets.UTF_8)
        ).identity(identity).grant(grant).build();

        System.out.println(token.toJwt());

        return token.toJwt();
    }

    // =========================================================================
    // Database
    // =========================================================================

    /** TODO: Since the server only serves to route messages
     *  the call records should not persevere on the server,
     *  they should be routed to the recipient of the action,
     *  as long as they are online or cache if they are offline
     */
    private void createCallRecord(String callId,
                                   String callerUserId,
                                   String calleeUserId,
                                   String callType) {
        logger.info("Creating an entry for a call");
        String sql = """
    INSERT INTO call_records (
        call_id, caller_user_id, callee_user_id,
        call_type, state, started_at
    ) VALUES (?, ?, ?, ?, 'ringing', NOW())
    """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, callId);
            stmt.setString(2, callerUserId);
            stmt.setString(3, calleeUserId);
            stmt.setString(4, callType);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("createCallRecord error: " + e.getMessage());
        }
    }

    private void updateCallState(String callId,
                                  String state,
                                  Long answeredAtMs) {
        String sql = """
            UPDATE call_records
            SET state       = ?,
                answered_at = CASE WHEN ? IS NOT NULL
                                   THEN to_timestamp(? / 1000.0)
                                   ELSE answered_at
                              END
            WHERE call_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, state);
            if (answeredAtMs != null) {
                stmt.setLong(2, answeredAtMs);
                stmt.setLong(3, answeredAtMs);
            } else {
                stmt.setNull(2, java.sql.Types.BIGINT);
                stmt.setNull(3, java.sql.Types.BIGINT);
            }
            stmt.setString(4, callId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updateCallState error: " + e.getMessage());
        }
    }

    private void updateCallEnded(String callId, int durationSeconds) {
        String sql = """
            UPDATE calls
            SET state            = 'ended',
                ended_at         = NOW(),
                duration_seconds = ?
            WHERE call_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, durationSeconds);
            stmt.setString(2, callId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updateCallEnded error: " + e.getMessage());
        }
    }

    private boolean isInActiveCall(String userId) {
        return activeCalls.values().stream().anyMatch(state ->
                state.callerId().equals(userId)
                || state.calleeId().equals(userId));
    }

    private String getDisplayName(String userId) {
        String sql = "SELECT username FROM users WHERE user_id = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("display_name") : "Unknown";
            }

        } catch (SQLException e) {
            return "Unknown";
        }
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private CallRequest parseRequest(XMLEventReader reader) {
        String action       = null;
        String callId       = null;
        String to           = null;
        String callType     = "voice";
        String sdp          = null;
        String iceCandidate = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {

                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    if ("call".equals(name) && CALL_NS.equals(ns)) {
                        action   = getAttr(se, "action");
                        callType = getAttr(se, "type") != null
                                ? getAttr(se, "type") : "video";

                    }

                    switch (name) {
                        case "call_id"   -> callId       = readText(reader);
                        case "to"        -> to           = readText(reader);
                        case "sdp"       -> sdp          = readText(reader);
                        case "candidate" -> iceCandidate = readText(reader);
                    }
                }

                if (event.isEndElement()){
                    depth--;
                    if(event.asEndElement().getName().getLocalPart().equals("iq")) break;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("parseRequest error: " + e.getMessage());
            return null;
        }
        return new CallRequest(action, callId, to, callType, sdp, iceCandidate);
    }

    private String readText(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent e = reader.peek();
                if (e.isCharacters()) {
                    reader.nextEvent();
                    sb.append(e.asCharacters().getData());
                } else break;
            }
            if (reader.hasNext() && reader.peek().isEndElement()) {
                reader.nextEvent();
            }
        } catch (XMLStreamException ignored) {}
        return sb.toString().trim();
    }

    private String getAttr(StartElement el, String name) {
        Attribute attr = el.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private void sendError(Session s, String iqId, String cond) {
        s.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='cancel'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            cond
        ));
    }

    private void consumeElement(XMLEventReader reader) {
        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent e = reader.nextEvent();
                if (e.isStartElement()) depth++;
                if (e.isEndElement()) depth--;
            }
        } catch (XMLStreamException ignored) {}
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private String toBareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record CallRequest(
            String action,
            String callId,
            String to,
            String callType,
            String sdp,
            String iceCandidate
    ) {}

    /**
     * In-memory call state.
     * Keeps track of active calls without hitting the DB on every ICE candidate.
     */
    private record CallState(
            String callId,
            String callerId,
            String callerJid,
            String calleeId,
            String calleeJid,
            String callType,
            long startedAt,
            String state
    ) {
        CallState(String callId, String callerId, String callerJid,
                  String calleeId, String calleeJid,
                  String callType, long startedAt) {
            this(callId, callerId, callerJid, calleeId, calleeJid,
                    callType, startedAt, "ringing");
        }

        CallState withState(String newState) {
            return new CallState(callId, callerId, callerJid,
                    calleeId, calleeJid, callType, startedAt, newState);
        }
    }
}