package streammessenger.session;

import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import streammessenger.exception.StreamException;
import streammessenger.xep.sm.StreamManagementState;


/**
 * Represents a single client connection and ALL its associated state.
 *
 * Thread safety model:
 *  - AtomicReference for session state (frequent reads, rare writes)
 *  - AtomicLong for lastActivity (written on every stanza, read by reaper)
 *  - ReentrantLock (writeLock) for socket output (prevent XML interleaving)
 *  - volatile for sslSocket, contactId, resource, jid (written once after init)
 *  - StreamManagementState has its own internal locking
 */
public class Session {

    private static final Logger logger = Logger.getLogger(Session.class.getName());
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(Session.class);

    // -------------------------------------------------------------------------
    // Immutable identity - set at construction, never change
    // -------------------------------------------------------------------------
    private final String uid;
    private final Socket socket;

    // -------------------------------------------------------------------------
    // Mutable connection state
    // -------------------------------------------------------------------------
    private final AtomicReference<SessionState> state =
            new AtomicReference<>(SessionState.INITIAL);

    private final AtomicLong lastActivity =
            new AtomicLong(System.currentTimeMillis());

    // Written once during TLS upgrade, read many times after
    private volatile SSLSocket sslSocket;

    // Written once during authentication, read many times after
    private volatile String contactId;
    private volatile String resource;
    private volatile String jid; // full JID: user@domain/resource

    // Add to Session.java

    // Device ID (from device registry) - set after resource binding
    private volatile String deviceId;


    // -------------------------------------------------------------------------
    // Stream Management (XEP-0198) state - null if SM not enabled
    // -------------------------------------------------------------------------
    private volatile StreamManagementState smState;

    // -------------------------------------------------------------------------
    // Write serialization
    // Ensures XML stanzas are never interleaved on the wire
    // -------------------------------------------------------------------------
    private final ReentrantLock writeLock = new ReentrantLock();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public Session(Socket socket, String uid) {
        if (socket == null) throw new IllegalArgumentException("Socket cannot be null");
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("UID cannot be null/empty");
        this.socket = socket;
        this.uid = uid;
    }


    public String getDeviceId() { return deviceId; }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    // =========================================================================
    // XML Write Operations
    // ALL output to the client goes through writeXML()
    // =========================================================================

    /**
     * Writes raw XML to the client.
     *
     * Chooses SSL socket if TLS has been negotiated, plain socket otherwise.
     * Acquires the write lock to prevent concurrent writes from interleaving XML.
     *
     * If Stream Management is enabled, the stanza is added to the unacked queue
     * before being sent so it can be retransmitted on session resumption.
     *
     * @param xml The complete XML string to send
     * @return true if written successfully, false if the socket is broken
     */
    public boolean writeXML(String xml) {
        writeLock.lock();
        try {
            OutputStream os = resolveOutputStream();
            if (os == null) return false;

            // If SM is active and this looks like a stanza (not a SM control frame),
            // add to unacked queue before sending
            if (smState != null && smState.isEnabled() && isStanza(xml)) {
                smState.trackOutbound(xml);
            }

            OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            writer.write(xml);
            writer.flush();
            touchActivity();
            return true;

        } catch (IOException e) {
            logger.warning("Write failed uid=" + uid + ": " + e.getMessage());
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a well-formed XMPP stream error element.
     * RFC 6120 §4.9
     */
    public boolean writeStreamError(StreamException.Condition condition, String text) {
        String xml = buildStreamError(condition, text);
        return writeXML(xml);
    }

    /**
     * Closes the stream gracefully - sends closing tag first so client knows
     * this is an intentional disconnect, not a crash.
     */
    public void closeGracefully() {
        boolean transitioned =
                state.compareAndSet(SessionState.AUTHENTICATED, SessionState.CLOSING) ||
                        state.compareAndSet(SessionState.STREAM_OPENED, SessionState.CLOSING) ||
                        state.compareAndSet(SessionState.STARTTLS_NEGOTIATED, SessionState.CLOSING);

        if (transitioned) {
            writeXML("</stream:stream>");
        }
        closeQuietly();
    }

    /**
     * Closes all sockets without sending any XML.
     * Safe to call multiple times - idempotent.
     * Used in error paths and as the final step of closeGracefully().
     */
    public void closeQuietly() {
        state.set(SessionState.CLOSED);
        closeSilently(sslSocket);
        closeSilently(socket);
    }

    // =========================================================================
    // Stream Management (XEP-0198)
    // =========================================================================

    /**
     * Enables Stream Management for this session.
     *
     * Called by StreamManagementHandler after the client sends <enable/>.
     * Creates a new StreamManagementState and attaches it to this session.
     *
     * @param smId      The SM session ID (for resumption)
     * @param resumable Whether this session supports resumption after disconnect
     */
    public void enableStreamManagement(String smId, boolean resumable) {
        this.smState = new StreamManagementState(smId, resumable);
        logger.info("Stream management enabled uid=" + uid
                + " smId=" + smId + " resumable=" + resumable);
    }

    /**
     * Returns true if Stream Management has been enabled for this session.
     */
    public boolean hasStreamManagement() {
        return smState != null && smState.isEnabled();
    }

    /**
     * Returns the Stream Management state, or null if SM is not enabled.
     */
    public StreamManagementState getSmState() {
        return smState;
    }

    /**
     * Processes a client acknowledgment (<a h='N'/>).
     * Removes all stanzas up to sequence number N from the unacked queue.
     *
     * @param h The number of stanzas the client has processed
     */
    public void processAck(long h) {
        if (smState != null) {
            smState.processClientAck(h);
        }
    }

    /**
     * Records that the server has received one more stanza from the client.
     * Used to build the h value in <a/> responses.
     */
    public void incrementInboundCount() {
        if (smState != null) {
            smState.incrementInbound();
        }
    }

    /**
     * Returns the number of stanzas received from the client since SM was enabled.
     * Sent back to client in <a h='N'/> responses.
     */
    public long getInboundCount() {
        return smState != null ? smState.getInboundCount() : 0;
    }

    // =========================================================================
    // State management
    // =========================================================================

    public SessionState getSessionState() {
        return state.get();
    }

    public void setSessionState(SessionState newState) {
        SessionState old = state.getAndSet(newState);
        logger.fine("Session uid=" + uid + " state: " + old + " → " + newState);
    }

    /**
     * Atomic compare-and-set for state transitions.
     * Use this when the transition should only happen from a specific state
     * to prevent race conditions.
     */
    public boolean compareAndSetState(SessionState expected, SessionState update) {
        return state.compareAndSet(expected, update);
    }

    public boolean isAuthenticated() {
        return state.get() == SessionState.AUTHENTICATED;
    }

    public boolean isClosed() {
        return state.get() == SessionState.CLOSED;
    }

    // =========================================================================
    // Activity tracking
    // =========================================================================

    /**
     * Updates the last activity timestamp to now.
     * Called on every inbound stanza and every successful write.
     * The session reaper uses this to detect idle sessions.
     */
    public void touchActivity() {
        lastActivity.set(System.currentTimeMillis());
    }

    public long getLastActivity() {
        return lastActivity.get();
    }

    // =========================================================================
    // Getters and setters
    // =========================================================================

    public String getUid() { return uid; }

    public Socket getSocket() { return socket; }

    public SSLSocket getSSLSocket() { return sslSocket; }

    public void setSSlSocket(SSLSocket sslSocket) {
        this.sslSocket = sslSocket;
    }

    public String getContactId() { return contactId; }

    public void setContactId(String contactId) {
        this.contactId = contactId;
    }

    public String getResource() { return resource; }

    /**
     * Sets the resource and rebuilds the full JID.
     * Called during resource binding (after authentication).
     */
    public void setResource(String resource) {
        this.resource = resource;
        if (contactId != null) {
            this.jid = contactId + (resource != null ? "/" + resource : "");
        }
    }

    public String getJid() {
        return jid != null ? jid : contactId;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns the correct OutputStream based on TLS state.
     * After STARTTLS: SSLSocket output.
     * Before STARTTLS: plain socket output.
     */
    private OutputStream resolveOutputStream() {
        try {
            if (sslSocket != null && !sslSocket.isClosed()) {
                return sslSocket.getOutputStream();
            }
            if (socket != null && !socket.isClosed()) {
                return socket.getOutputStream();
            }
        } catch (IOException e) {
            logger.warning("Cannot resolve OutputStream uid=" + uid + ": " + e.getMessage());
        }
        return null;
    }

    private String buildStreamError(StreamException.Condition condition, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("<stream:error>");
        sb.append("<").append(condition.getXmlValue())
                .append(" xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>");
        if (text != null && !text.isBlank()) {
            sb.append("<text xmlns='urn:ietf:params:xml:ns:xmpp-streams'>")
                    .append(escapeXml(text))
                    .append("</text>");
        }
        sb.append("</stream:error>");
        return sb.toString();
    }

    /**
     * Determines if an XML string represents a stanza (message/presence/iq)
     * vs a stream-level control frame (sm ack, features, etc.)
     * Only stanzas are tracked by Stream Management.
     */
    private boolean isStanza(String xml) {
        String trimmed = xml.stripLeading();
        return trimmed.startsWith("<message") ||
                trimmed.startsWith("<presence") ||
                trimmed.startsWith("<iq");
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    private void closeSilently(java.io.Closeable resource) {
        if (resource != null) {
            try { resource.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public String toString() {
        return String.format("Session{uid=%s, jid=%s, state=%s, sm=%s}",
                uid, jid, state.get(), smState != null ? "enabled" : "disabled");
    }


    /**
     * Restores a previously saved SM state during session resumption.
     *
     * Unlike enableStreamManagement() which creates a NEW state,
     * this method CONTINUES an existing state - preserving the
     * sequence numbers, unacked queue, and inbound count.
     *
     * The critical difference:
     *   enableStreamManagement()           → new state, seq starts at 0
     *   enableStreamManagementWithState()  → existing state, seq continues
     *
     * This is what makes resumption work:
     *   Old session: outboundSeq=10, inboundCount=7, unacked=[8,9,10]
     *   Client disconnects having received up to seq=7
     *   Client reconnects: sends <resume h='7'/>
     *   Server: processClientAck(7) → removes seqNums ≤ 7
     *           unacked now = [8, 9, 10]
     *   enableStreamManagementWithState(existingState)
     *   Retransmits 8, 9, 10 → client receives what it missed
     *
     * @param existingState The SM state retrieved from smStateStore
     */
    public void enableStreamManagementWithState(
            StreamManagementState existingState) {
        this.smState = existingState;
        logger.info(String.format(
                "SM state restored: uid=%s smId=%s " +
                        "outSeq=%d inCount=%d unacked=%d",
                uid,
                existingState.getSmId(),
                existingState.getOutboundSeq(),
                existingState.getInboundCount(),
                existingState.getUnackedCount()
        ));
    }
}