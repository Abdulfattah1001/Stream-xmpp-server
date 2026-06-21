package streammessenger.stanza;

import com.xmpp.db.DatabaseManager;
import com.xmpp.session.Session;
import com.xmpp.session.SessionRegistry;
import com.xmpp.push.PushNotificationService;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles call signaling for Twilio-based video calls.
 *
 * Namespace: urn:xmpp:call:0
 *
 * Operations:
 *   invite   → Initiate a call (caller → callee)
 *   accept   → Callee accepts
 *   decline  → Callee declines
 *   end      → Either party hangs up
 *   busy     → Callee is in another call
 *
 * The XMPP server does NOT handle WebRTC SDP/ICE.
 * Twilio handles all media negotiation internally.
 * We just route call control messages.
 */
public final class CallHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(CallHandler.class.getName());

    private static final String CALL_NS = "urn:xmpp:call:0";

    private final SessionRegistry registry;
    private final DatabaseManager db;
    private final PushNotificationService pushService;
    private final ConnectionPool pool;

    public CallHandler(SessionRegistry registry,
                        DatabaseManager db,
                        PushNotificationService pushService,
                        ConnectionPool pool) {
        this.registry = registry;
        this.db = db;
        this.pushService = pushService;
        this.pool = pool;
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

        switch (req.action()) {
            case "invite"  -> handleInvite(req, iqId, session);
            case "accept"  -> handleAccept(req, iqId, session);
            case "decline" -> handleDecline(req, iqId, session);
            case "end"     -> handleEnd(req, iqId, session);
            case "busy"    -> handleBusy(req, iqId, session);
        }
    }

    // =========================================================================
    // Invite
    // =========================================================================

    /**
     * Caller initiates a call.
     * Generates a Twilio room name (call_id UUID).
     * Routes invite to callee + sends push notification.
     */
    private void handleInvite(CallRequest req,
                               String iqId,
                               Session callerSession) {
        if (req.calleeJid() == null) {
            sendError(callerSession, iqId, "bad-request");
            return;
        }

        String callerId = extractUserId(callerSession.getContactId());
        String calleeId = extractUserId(req.calleeJid());
        String callId = UUID.randomUUID().toString();
        // Twilio room name = call_id (same UUID)
        String roomName = "call-" + callId;

        // 1. Create call record
        createCallRecord(callId, roomName, callerId, calleeId,
                req.callType());

        // 2. Confirm to caller
        callerSession.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<call xmlns='%s' action='ringing'>" +
            "<call_id>%s</call_id>" +
            "<twilio_room>%s</twilio_room>" +
            "</call></iq>",
            escapeXml(iqId), CALL_NS,
            callId, roomName
        ));

        // 3. Route invite to callee
        String inviteStanza = String.format(
            "<iq type='set' from='%s' to='%s'>" +
            "<call xmlns='%s' action='incoming' type='%s'>" +
            "<call_id>%s</call_id>" +
            "<twilio_room>%s</twilio_room>" +
            "<caller_jid>%s</caller_jid>" +
            "</call></iq>",
            escapeXml(callerSession.getContactId()),
            escapeXml(req.calleeJid()),
            CALL_NS,
            escapeXml(req.callType()),
            callId, roomName,
            escapeXml(callerSession.getContactId())
        );

        boolean delivered = registry.getByContactId(req.calleeJid())
                .filter(Session::isAuthenticated)
                .map(s -> s.writeXML(inviteStanza))
                .orElse(false);

        // 4. ALWAYS send push notification (calls must wake up the device)
        String callerName = db.getDisplayName(callerId);
        pushService.sendCallNotification(
                calleeId,
                callerName,
                req.callType(),
                callId,
                roomName
        );

        logger.info("Call invited: " + callId
                + " from=" + callerId + " to=" + calleeId
                + " delivered=" + delivered);
    }

    // =========================================================================
    // Accept
    // =========================================================================

    private void handleAccept(CallRequest req,
                               String iqId,
                               Session calleeSession) {
        if (req.callId() == null) {
            sendError(calleeSession, iqId, "bad-request");
            return;
        }

        // Update call state
        updateCallState(req.callId(), "accepted",
                System.currentTimeMillis() / 1000);

        // Confirm to callee
        calleeSession.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));

        // Notify caller that callee accepted
        String callerJid = getCallerJid(req.callId());
        if (callerJid != null) {
            registry.getByContactId(callerJid)
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(String.format(
                        "<message from='%s'>" +
                        "<call xmlns='%s' action='accepted'>" +
                        "<call_id>%s</call_id>" +
                        "</call></message>",
                        escapeXml(calleeSession.getContactId()),
                        CALL_NS,
                        escapeXml(req.callId())
                    )));
        }

        logger.info("Call accepted: " + req.callId());
    }

    // =========================================================================
    // Decline / End / Busy (similar pattern)
    // =========================================================================

    private void handleDecline(CallRequest req, String iqId, Session session) {
        updateCallState(req.callId(), "declined", null);
        notifyCaller(req.callId(), "declined", session.getContactId());
        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));
    }

    private void handleEnd(CallRequest req, String iqId, Session session) {
        // Calculate duration
        long endedAt = System.currentTimeMillis() / 1000;
        long durationSeconds = computeDuration(req.callId(), endedAt);

        updateCallEnded(req.callId(), durationSeconds);

        // Notify the other party
        String otherJid = getOtherPartyJid(req.callId(), session.getContactId());
        if (otherJid != null) {
            registry.getByContactId(otherJid)
                    .filter(Session::isAuthenticated)
                    .ifPresent(s -> s.writeXML(String.format(
                        "<message from='%s'>" +
                        "<call xmlns='%s' action='ended'>" +
                        "<call_id>%s</call_id>" +
                        "<duration>%d</duration>" +
                        "</call></message>",
                        escapeXml(session.getContactId()),
                        CALL_NS,
                        escapeXml(req.callId()),
                        durationSeconds
                    )));
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));
    }

    private void handleBusy(CallRequest req, String iqId, Session session) {
        updateCallState(req.callId(), "busy", null);
        notifyCaller(req.callId(), "busy", session.getContactId());
        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));
    }

    // =========================================================================
    // Database
    // =========================================================================

    private void createCallRecord(String callId,
                                   String roomName,
                                   String callerId,
                                   String calleeId,
                                   String callType) {
        String sql = """
            INSERT INTO call_records (
                call_id, twilio_room_sid, caller_user_id,
                callee_user_id, call_type, state, started_at
            ) VALUES (?::uuid, ?, ?, ?, ?, 'ringing', NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, callId);
            stmt.setString(2, roomName);
            stmt.setString(3, callerId);
            stmt.setString(4, calleeId);
            stmt.setString(5, callType);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("createCallRecord error: " + e.getMessage());
        }
    }

    private void updateCallState(String callId, String state, Long acceptedAt) {
        // pseudo: UPDATE call_records SET state = ?, accepted_at = ? WHERE call_id = ?
    }

    private void updateCallEnded(String callId, long duration) {
        // pseudo: UPDATE call_records SET state='ended', ended_at=NOW(), duration_seconds=?
    }

    private String getCallerJid(String callId) {
        // pseudo: SELECT u.jid FROM call_records cr JOIN users u ON u.user_id = cr.caller_user_id WHERE call_id = ?
        return null;
    }

    private String getOtherPartyJid(String callId, String myJid) {
        // pseudo: return the JID that is NOT mine for this call
        return null;
    }

    private long computeDuration(String callId, long endedAtSec) {
        // pseudo: get accepted_at from DB, return endedAt - acceptedAt
        return 0;
    }

    // ... other helper stubs (notifyCaller, parseRequest, escapeXml etc.)
    private record CallRequest(
            String action,
            String calleeJid,
            String callId,
            String callType
    ) {}

    private void notifyCaller(String callId, String action, String fromJid) {}
    private CallRequest parseRequest(XMLEventReader r) { return null; }
    private String extractUserId(String jid) { return null; }
    private String getAttr(StartElement e, String n) { return null; }
    private void sendError(Session s, String id, String c) {}
    private void consumeElement(XMLEventReader r) {}
    private String escapeXml(String s) { return s; }
}