package streammessenger.connection;


import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import streammessenger.exception.StartTLSException;
import streammessenger.exception.StreamException;
import streammessenger.metrics.ServerMetrics;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.session.SessionState;
import streammessenger.stream.XMPPStreamProcessor;

/**
 * Manages the full lifecycle of one client connection.
 * <p>
 * Flow:
 *  1. Plain TCP socket accepted
 *  2. Plain XMPP stream opened, STARTTLS feature advertised
 *  3. Client requests STARTTLS -> send <proceed/> -> upgrade socket to TLS
 *  4. Client reopens XMPP stream over TLS
 *  5. Server advertises SASL PLAIN
 *  6. Client authenticates
 *  7. Stanza exchange until disconnect or timeout
 * <p>
 * One instance per connection. Not shared across threads.
 */
public final class ConnectionHandler implements Runnable {

    private static final Logger logger = Logger.getLogger(ConnectionHandler.class.getName());

    private final Socket socket;
    private final String sessionId;
    private final TLSUpgrader tlsUpgrader;
    private final XMPPStreamProcessor streamProcessor;
    private final SessionRegistry registry;
    private final ServerMetrics metrics;
    private final ScheduledExecutorService scheduler;

    public ConnectionHandler(
            Socket socket,
            String sessionId,
            TLSUpgrader tlsUpgrader,
            XMPPStreamProcessor streamProcessor,
            SessionRegistry registry,
            ServerMetrics metrics, ScheduledExecutorService scheduledExecutorService) {
        this.socket = socket;
        this.sessionId = sessionId;
        this.tlsUpgrader = tlsUpgrader;
        this.streamProcessor = streamProcessor;
        this.registry = registry;
        this.metrics = metrics;
        this.scheduler = scheduledExecutorService;
    }

    @Override
    public void run() {
        Session session = new Session(socket, sessionId, scheduler);
        registry.register(session);
        metrics.connectionAccepted();

        logger.info("Connection accepted sessionId=" + session
                + " from=" + socket.getInetAddress().getHostAddress());

        try {
            runConnectionLifecycle(session);
        } catch (Exception e) {
            // Top-level safety net - should never reach here in normal operation
            logger.warning("Unhandled exception in connection sessionId=" + sessionId
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            cleanup(session);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Drives the full connection state machine.
     */
    private void runConnectionLifecycle(Session session) {
        String remoteAddr = socket.getInetAddress().getHostAddress();

        // Check if the connection comes from local Nginx reverse proxy
        boolean isProxyTls = "127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr);

        if (isProxyTls) {
            // Option A: Nginx already terminated TLS at the edge!
            // Move directly to STARTTLS_NEGOTIATED so SASL features are advertised immediately.
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            session.setProxyTls(true);
            try {
                processStream(session);
            } catch (StartTLSException nested) {
                logger.warning("Unexpected STARTTLS from proxy connection sessionId=" + sessionId);
                session.writeStreamError(
                        StreamException.Condition.POLICY_VIOLATION,
                        "TLS already terminated by proxy"
                );
            }
        } else {
            // Fallback: Direct connection on 5222 requires standard in-band STARTTLS
            try {
                // Phase 1: Plain stream - expect STARTTLS
                session.setSessionState(SessionState.STREAM_OPENED);
                processStream(session);

            } catch (StartTLSException e) {
                // Phase 2: TLS upgrade
                SSLSocket sslSocket = performTLSUpgrade(session);
                if (sslSocket == null) return;

                // Phase 3: TLS stream - expect SASL auth then stanza exchange
                try {
                    processStream(session);
                } catch (StartTLSException nested) {
                    logger.warning("Nested STARTTLS attempt sessionId=" + sessionId);
                    session.writeStreamError(
                            StreamException.Condition.POLICY_VIOLATION,
                            "STARTTLS already negotiated"
                    );
                }
            }
        }
    }
    private void runConnectionLifecycleLegacy(Session session) {
        try {
            // Phase 1: Plain stream - expect STARTTLS
            session.setSessionState(SessionState.STREAM_OPENED);
            processStream(session);

        } catch (StartTLSException e) {
            // Phase 2: TLS upgrade
            SSLSocket sslSocket = performTLSUpgrade(session);
            if (sslSocket == null) return; // Upgrade failed - cleanup will handle it during the routine cleanup

            // Phase 3: TLS stream - expect SASL auth then stanza exchange
            try {
                processStream(session);
            } catch (StartTLSException nested) {
                // STARTTLS inside a TLS stream is a protocol error
                logger.warning("Nested STARTTLS attempt sessionId=" + sessionId);
                session.writeStreamError(
                    StreamException.Condition.POLICY_VIOLATION,
                    "STARTTLS already negotiated"
                );
            }
        }
    }

    /**
     * Runs one complete XML stream from open to close.
     * Returns normally on clean close, throws StartTLSException on STARTTLS request.
     */
    private void processStream(Session session) throws StartTLSException {
        InputStream inputStream = resolveInputStream(session);
        if (inputStream == null) return;

        XMLInputFactory factory = buildSecureXMLFactory();
        XMLEventReader reader = null;

        try {
            reader = factory.createXMLEventReader(inputStream);
            streamProcessor.process(reader, session);

        } catch (StartTLSException e) {
            throw e; // Propagate intentionally to be handled outside with the custom STARTTLSException handler

        } catch (SocketTimeoutException e) {
            logger.info("Socket timeout for sessionId=" + sessionId + " (client idle). Closing.");
            session.writeStreamError(
                StreamException.Condition.CONNECTION_TIMEOUT, "Idle timeout"
            );

        } catch (XMLStreamException e) {
            logger.warning("XML parse error sessionId=" + sessionId + ": " + e.getMessage());
            session.writeStreamError(
                StreamException.Condition.NOT_WELL_FORMED,
                "XML parse error"
            );

        } catch (IOException e) {
            // Client disconnected - not an error, just clean up
            logger.info("Client disconnected sessionId=" + sessionId + ": " + e.getMessage());

        } catch (Exception e) {
            logger.warning("Stream processing error sessionId=" + sessionId
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            session.writeStreamError(
                StreamException.Condition.INTERNAL_SERVER_ERROR,
                null
            );
        } finally {
            closeReader(reader);
        }
    }

    /**
     * Performs the TLS upgrade after <proceed/> has been sent.
     *
     * @return The new SSLSocket, or null if upgrade failed
     */
    private SSLSocket performTLSUpgrade(Session session) {
        try {
            SSLSocket sslSocket = tlsUpgrader.upgrade(socket);
            session.setSSlSocket(sslSocket);
            session.setSessionState(SessionState.STARTTLS_NEGOTIATED);
            metrics.tlsSuccess();
            logger.info("TLS upgrade successful sessionID=" + sessionId);
            return sslSocket;

        } catch (SSLHandshakeException e) {
            metrics.tlsFailure();
            logger.warning("TLS handshake failed sessionId=" + sessionId + ": " + e.getMessage());
            return null;

        } catch (IOException e) {
            metrics.tlsFailure();
            logger.warning("TLS upgrade I/O error sessionId=" + sessionId + ": " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the appropriate InputStream for the current session state.
     * After STARTTLS: read from SSLSocket.
     * Before STARTTLS: read from plain socket.
     */
    private InputStream resolveInputStreamLegacy(Session session) {
        try {
            if (session.getSessionState() == SessionState.STARTTLS_NEGOTIATED
                    || session.getSessionState() == SessionState.AUTHENTICATED) {
                SSLSocket ssl = session.getSSLSocket();
                if (ssl != null && !ssl.isClosed()) {
                    return ssl.getInputStream();
                }
            }
            if (!socket.isClosed()) {
                return socket.getInputStream();
            }
        } catch (IOException e) {
            logger.warning("Cannot get InputStream for session=" + sessionId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the appropriate InputStream for the current session state.
     * - If upgraded in-band locally: read from SSLSocket.
     * - If proxied via Nginx or plain TCP: read from plain Socket.
     */
    private InputStream resolveInputStream(Session session) {
        try {
            // 1. In-band STARTTLS mode: read from the upgraded SSLSocket if present
            SSLSocket ssl = session.getSSLSocket();
            if (ssl != null && !ssl.isClosed()) {
                return ssl.getInputStream();
            }

            // 2. Proxy TLS mode (Nginx) OR initial plain TCP stream: read from underlying socket
            if (!socket.isClosed()) {
                return socket.getInputStream();
            }
        } catch (IOException e) {
            logger.warning("Cannot get InputStream for session=" + sessionId + ": " + e.getMessage());
        }
        return null;
    }
    /**
     * Creates an XMLInputFactory with external entity processing disabled.
     * This prevents XXE (XML External Entity) attacks.
     */
    private XMLInputFactory buildSecureXMLFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }

    private void closeReader(XMLEventReader reader) {
        if (reader != null) {
            try { reader.close(); } catch (XMLStreamException ignored) {}
        }
    }

    /**
     * Always called when a connection handler exits - regardless of how.
     * Ensures the session is removed from the registry and metrics decremented.
     */
    private void cleanup(Session session) {
        streamProcessor.onSessionDisconnect(session);
        registry.remove(session);

        if (session.isAuthenticated()) {
            metrics.sessionDeAuthenticated();
        }

        session.closeQuietly();
        metrics.connectionClosed();

        logger.info("Connection closed sessionId=" + sessionId
                + " contactId=" + session.getContactId());
    }
}