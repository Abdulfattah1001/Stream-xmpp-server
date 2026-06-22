package streammessenger.stream;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import streammessenger.auth.AuthManager;
import streammessenger.call.CallSignalingHandler;
import streammessenger.db.BlogDatabaseManager;
import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.exception.StartTLSException;
import streammessenger.exception.StreamException;
import streammessenger.features.CollaborativeNoteHandler;
import streammessenger.group.handler.GroupStanzaHandler;
import streammessenger.metrics.ServerMetrics;
import streammessenger.mutlidevice.MultiDeviceMessageHandler;
import streammessenger.push.PushNotificationService;
import streammessenger.roster.RosterManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.session.SessionState;
import streammessenger.stanza.AuthHandler;
import streammessenger.stanza.BlogHandler;
import streammessenger.stanza.CarbonHandler;
import streammessenger.stanza.EncryptedMessageHandler;
import streammessenger.stanza.IQHandler;
import streammessenger.stanza.PresenceHandler;
import streammessenger.stanza.StanzaHandler;
import streammessenger.stanza.StatusHandler;
import streammessenger.stanza.VerifiedAccountHandler;
import streammessenger.xep.sm.StreamManagementHandler;


/**
 * Core XMPP stream processor.
 * <p>
 * Owns the stanza handler registry. All handlers are stateless singletons
 * created once and reused across all connections.
 * <p>
 * Dispatches inbound XML elements to the correct handler.
 * Manages stream-level elements directly (stream open, STARTTLS, SM acks).
 */
public final class XMPPStreamProcessor {

    private static final Logger logger =
            Logger.getLogger(XMPPStreamProcessor.class.getName());

    private static final String TLS_NS  = "urn:ietf:params:xml:ns:xmpp-tls";
    private static final String SASL_NS = "urn:ietf:params:xml:ns:xmpp-sasl";
    private static final String SM_NS   = "urn:xmpp:sm:3";
    private static final String BIND_NS = "urn:ietf:params:xml:ns:xmpp-bind";
    private static final String GROUP_NS = "urn:xmpp:group:0";

    // MUC routing
    private final String mucDomain;          // "conference.yourdomain.com"
    private volatile GroupStanzaHandler groupHandler;
    // Stanza handlers - stateless singletons, keyed by element local name
    private final Map<String, StanzaHandler> handlers;

    // SM handler is separate (handles multiple element types + has lifecycle)
    private final StreamManagementHandler smHandler;
    private final CarbonHandler carbonHandler;
    private final MultiDeviceMessageHandler multiDeviceHandler;

    private final ServerMetrics metrics;
    private final DatabaseManager db;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10);

    public XMPPStreamProcessor(DatabaseManager db,
                               SessionRegistry registry,
                               AuthManager authManager,
                               RosterManager rosterManager,
                               ServerMetrics metrics, ConnectionPool pool,
                               CarbonHandler carbonHandler,
                               MultiDeviceMessageHandler multiDeviceMessageHandler,
                               CallSignalingHandler callHandler) {
        this.db     = db;
        this.metrics = metrics;
        this.smHandler = new StreamManagementHandler(registry);

        this.carbonHandler = carbonHandler;
        this.multiDeviceHandler = multiDeviceMessageHandler;

        this.mucDomain = "conference"+"@omnyrex.com";

        // Build handler registry - one instance per handler, shared across all connections
        this.handlers = new HashMap<>();
        this.handlers.put("auth",     new AuthHandler(authManager));
        //TODO: Not encrypted message:this.handlers.put("message",  new MessageHandler(registry, db, metrics));
        this.handlers.put("message", new EncryptedMessageHandler(registry, db, metrics));
        this.handlers.put("presence", new PresenceHandler(registry, db));
        this.handlers.put("iq",       new IQHandler(db, registry, rosterManager, callHandler));
        this.handlers.put("status-iq",new StatusHandler(db, registry));
        this.handlers.put("call",     callHandler);
        this.handlers.put("note",     new CollaborativeNoteHandler(pool, registry));
        //this.handlers.put("schedule", new ScheduledMessageHandler(pool, db, registry, (MessageHandler) this.handlers.get("message")));
        this.handlers.put("verified", new VerifiedAccountHandler(pool, registry));
        this.handlers.put("blog", new BlogHandler(db, registry, new BlogDatabaseManager(pool)));
    }

    /**
     * Runs the XML stream processing loop.
     * <p>
     * Blocks until:
     *  - Client closes the stream cleanly (</stream:stream>)
     *  - Client disconnects (IOException)
     *  - XML parse error (XMLStreamException)
     *  - STARTTLS requested (StartTLSException - intentional control flow)
     */
    public void process(XMLEventReader reader, Session session)
            throws StartTLSException, XMLStreamException, IOException {

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            // Skip insignificant whitespace between stanzas
            if (event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

            //Handled the opening tag of every stanza sent
            if (event.isStartElement()) {
                handleStartElement(event.asStartElement(), reader, session);
            }

            if (event.isEndElement()) {
                String localName = event.asEndElement().getName().getLocalPart();
                if ("stream".equals(localName)) {
                    session.writeXML("</stream:stream>");
                    return;
                }
            }
        }
    }

    /**
     * Notifies this processor that a session has disconnected.
     * Saves SM state if the session had SM enabled.
     */
    public void onSessionDisconnect(Session session) {
        db.updateUserLastSeen(session.getJid());
        smHandler.onSessionDisconnect(session);
    }

    public void registerGroupHandler(GroupStanzaHandler handler) {
        this.groupHandler = handler;
        logger.info("Group stanza handler registered for domain: " + mucDomain);
    }

    public void shutdown() {
        smHandler.shutdown();
    }

    // =========================================================================
    // Private dispatch
    // =========================================================================


    // The resume element must be handled in the STARTTLS_NEGOTIATED state
    // BEFORE we send SASL features - this is the fast path
    private void handleStartElement(StartElement element,
                                    XMLEventReader reader,
                                    Session session)
            throws StartTLSException, XMLStreamException, IOException {

        String localName = element.getName().getLocalPart();
        String ns = element.getName().getNamespaceURI();
        if (ns == null) ns = "";

        switch (localName) {

            case "stream" -> handleStreamOpen(element, session);

            case "starttls" -> handleStartTLS(session);

            // ─────────────────────────────────────────────────────────────
            // RESUME - must be handled BEFORE offering SASL features
            // This is the fast reconnect path
            // ─────────────────────────────────────────────────────────────
            case "resume" -> {
                if (!SM_NS.equals(ns)) {
                    consumeElement(reader);
                    break;
                }

                /// Armed robber state
                if (session.getSessionState()
                        != SessionState.STARTTLS_NEGOTIATED) {
                    session.writeXML(
                            "<failed xmlns='" + SM_NS + "'>" +
                                    "<unexpected-request " +
                                    "xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                                    "</failed>"
                    );
                    break;
                }

                // Attempt resumption
                // Returns true = session restored, ready for stanzas
                // Returns false = client must do full auth
                boolean resumed = smHandler.handleResume(
                        element, reader, session);

                if (resumed) {
                    metrics.sessionAuthenticated();
                    logger.info("Session resumed via SM uid="
                            + session.getSessionId()
                            + " contactId=" + session.getContactId());

                    // Deliver any offline messages that arrived
                    // while the client was disconnected
                    deliverOfflineMessagesAfterResume(session);
                }
                // If !resumed: loop continues, client sends SASL auth next
            }

            // ─────────────────────────────────────────────────────────────
            // SM control frames (only valid after session is established)
            // ─────────────────────────────────────────────────────────────
            case "enable" -> {
                if (SM_NS.equals(ns)) {
                    smHandler.handleEnable(element, session);
                } else {
                    consumeElement(reader);
                }
            }

            case "r" -> {
                if (SM_NS.equals(ns)) {
                    smHandler.handleRequestAck(session);
                    session.touchActivity();
                } else {
                    consumeElement(reader);
                }
            }

            case "a" -> {
                if (SM_NS.equals(ns)) {
                    smHandler.handleAck(element, session);
                } else {
                    consumeElement(reader);
                }
            }

            // ─────────────────────────────────────────────────────────────
            // Authentication
            // ─────────────────────────────────────────────────────────────
            case "auth" -> {
                StanzaHandler handler = handlers.get("auth");
                if (handler != null) {
                    handler.handle(element, reader, session);
                    metrics.stanzaProcessed();
                }
            }

            // ─────────────────────────────────────────────────────────────
            // Stanzas - require authentication
            // ─────────────────────────────────────────────────────────────
            case "messageold", "presence", "iqold" -> {
                if (!session.isAuthenticated()) {
                    logger.warning("Unauthenticated stanza <"
                            + localName + "> uid=" + session.getSessionId());
                    session.writeStreamError(
                            StreamException.Condition.NOT_AUTHORIZED,
                            "Authentication required"
                    );
                    consumeElement(reader);
                    return;
                }

                session.touchActivity();
                session.incrementInboundCount();

                // Track for SM acks
                if (session.hasStreamManagement()) {
                    session.getSmState().incrementInbound();
                }

                // Peek at the child to route to specific handlers
                String iqNs = peekChildNamespace(reader, element);
                if (iqNs != null && iqNs.equals("http://jabber.org/protocol/muc")) {
                    if (groupHandler != null) {
                        groupHandler.handle(element, reader, session);
                        metrics.stanzaProcessed();
                    } else {
                        consumeElement(reader);
                    }
                }
                else {
                    StanzaHandler handler = handlers.get(localName);
                    if (handler != null) {
                        handler.handle(element, reader, session);
                        metrics.stanzaProcessed();
                    } else {
                        consumeElement(reader);
                    }
                }
            }

            case "iq" -> {
                session.touchActivity();
                session.incrementInboundCount();

                if (session.hasStreamManagement()) {
                    session.getSmState().incrementInbound();
                }

                // Peek at the child to route to specific handlers
                String iqNs = peekChildNamespace(reader, element);
                if (iqNs.equals(GROUP_NS)) {
                    if (groupHandler != null) {
                        groupHandler.handle(element, reader, session);
                        metrics.stanzaProcessed();
                    } else {
                        logger.warning("Group handler not registered");
                        consumeElement(reader);
                    }
                }
                else if ("urn:xmpp:carbons:2".equals(iqNs)
                        || "urn:xmpp:device:0".equals(iqNs)) {
                    carbonHandler.handle(element, reader, session);
                } else {
                    StanzaHandler handler = handlers.get(localName);
                    if (handler != null) {
                        handler.handle(element, reader, session);
                        metrics.stanzaProcessed();
                    } else {
                        consumeElement(reader);
                    }
                }

                metrics.stanzaProcessed();
            }

            // Update "message" case to handle multi-device:
            case "message" -> {
                session.touchActivity();
                session.incrementInboundCount();

                if (isGroupStanza(element, localName)) {
                    if (groupHandler != null) {
                        groupHandler.handle(element, reader, session);
                        metrics.stanzaProcessed();
                    } else {
                        consumeElement(reader);
                    }
                } else {
                    // Check if this is a multi-device encrypted message
                    if (isMultiDeviceMessage(element, reader)) {
                        multiDeviceHandler.handle(element, reader, session);
                    } else {
                        StanzaHandler handler = handlers.get("message");
                        if (handler != null) handler.handle(element, reader, session);
                    }
                }

                metrics.stanzaProcessed();
            }

            default -> {
                logger.fine("Unhandled: " + localName + " ns=" + ns);
                consumeElement(reader);
            }
        }
    }

    /**
     * Determines if a stanza should be routed to the group handler.
     * <p>
     * RULES:
     * <p>
     * 1. <message type='groupchat'>             → GROUP
     *    Standard XEP-0045 group chat message
     * <p>
     * 2. <message to='group@conference.domain'> → GROUP
     *    Message addressed to a MUC room
     * <p>
     * 3. <presence to='group@conference.domain/nick'> → GROUP
     *    Join/leave/update presence in a MUC room
     * <p>
     * 4. <iq>... <* xmlns='urn:xmpp:group:0'/> → GROUP
     *    Custom group management IQ
     * <p>
     * 5. Everything else → 1-1 handlers
     */
    private boolean isGroupStanza(StartElement element, String localName) {
        // Rule 1: groupchat message type
        if ("message".equals(localName)) {
            String type = getAttribute(element, "type");
            if ("groupchat".equals(type)) return true;
        }

        // Rule 2 & 3: addressed to MUC domain
        String to = getAttribute(element, "to");
        if (to != null && to.contains("@")) {
            int atIdx = to.indexOf('@');
            int slashIdx = to.indexOf('/');
            String domain = (slashIdx == -1)
                    ? to.substring(atIdx + 1)
                    : to.substring(atIdx + 1, slashIdx);

            if (mucDomain.equalsIgnoreCase(domain)) return true;
        }

        // Rule 4: IQ with group namespace
        // (requires peeking child - more expensive but accurate)
        if ("iq".equals(localName)) {
            // We can't easily peek without consuming; the group handler
            // itself will detect the child namespace and either handle
            // or pass through. For now, default to IQHandler unless we
            // know it's a group op.
            // Pattern: check for known group ID prefix in 'to'
            String iqTo = getAttribute(element, "to");
            if (iqTo != null && iqTo.contains(mucDomain)) return true;
        }

        return false;
    }

    private String getAttribute(StartElement element, String name) {
        Attribute attr = element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    // Helper to detect multi-device messages
    private boolean isMultiDeviceMessage(StartElement element, XMLEventReader reader) {
        //TODO:
        // Peek at first child without consuming
        // If it's <multi-encrypted xmlns='urn:xmpp:omemo:2'> → route to multi-device handler
        // This requires a lookahead mechanism - simpler: look at child namespaces
        // For now, we use a marker approach in MessageHandler
        return false; // MessageHandler will delegate
    }

    // Peek at child namespace without consuming it
    private String peekChildNamespace(XMLEventReader reader, StartElement parent) {
        try {
            if (reader.hasNext()) {
                XMLEvent next = reader.peek();
                if (next.isStartElement()) {
                    return next.asStartElement().getName().getNamespaceURI();
                }
            }
        } catch (XMLStreamException ignored) {}
        return null;
    }
    /**
     * After SM resumption, deliver any offline messages that arrived
     * while the client was disconnected.
     * <p>
     * <b>Note: This is DIFFERENT from the unacked queue retransmission.</b>
     * <p>
     *   SM retransmission:
     *     Stanzas WE sent but client didn't confirm receiving.
     *     These come from the unacked queue in SM state.
     *     The client was connected but the network dropped.
     * <p>
     *   Offline message delivery:
     *     Stanzas stored in the database while client was offline.
     *     The client was completely disconnected.
     * <p>
     * Both can happen simultaneously:
     *   1. SM retransmits missed in-flight stanzas
     *   2. Then offline stored messages are delivered
     */
    private void deliverOfflineMessagesAfterResume(Session session) {
        if (session.getContactId() == null) return;

        Thread.ofVirtual()
                .name("offline-delivery-" + session.getSessionId())
                .start(() -> {
                    try {
                        logger.info("Offline-delivery thread running");
                        java.util.List<DatabaseManager
                                .EncryptedOfflineMessage> messages =
                                db.fetchEncryptedOfflineMessages(session.getContactId());

                        if (messages.isEmpty()) return;

                        logger.info(String.format(
                                "Delivering %d offline messages after SM resume: %s",
                                messages.size(), session.getContactId()
                        ));

                        for (DatabaseManager
                                .EncryptedOfflineMessage msg : messages) {
                            String stanzaXml = buildOfflineStanza(msg);
                            session.writeXML(stanzaXml);
                        }

                    } catch (Exception e) {
                        logger.warning("Offline delivery after resume failed: "
                                + e.getMessage());
                    }
                });
    }

    private String buildOfflineStanza(
            DatabaseManager.EncryptedOfflineMessage msg) {

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "<message id='%s' from='%s' type='chat'>",
                escapeXml(msg.messageId()),
                escapeXml(msg.fromJid())
        ));

        sb.append(String.format(
                "<encrypted xmlns='urn:xmpp:e2ee:0' " +
                        "msg_type='%s' iv='%s'",
                escapeXml(msg.messageType()),
                escapeXml(msg.iv())
        ));

        if (msg.mediaStorageKey() != null) {
            sb.append(" storage_key='")
                    .append(escapeXml(msg.mediaStorageKey()))
                    .append("'");
        }
        if (msg.mimeType() != null) {
            sb.append(" mime='")
                    .append(escapeXml(msg.mimeType()))
                    .append("'");
        }
        if (msg.fileSizeBytes() > 0) {
            sb.append(" size='").append(msg.fileSizeBytes()).append("'");
        }

        sb.append(">");
        sb.append(msg.encryptedContent());
        sb.append("</encrypted>");

        // XEP-0203 Delayed Delivery timestamp
        // Tells the client when the message was originally sent
        sb.append(String.format(
                "<delay xmlns='urn:xmpp:delay' stamp='%s'/>",
                formatTimestamp(msg.createdAt())
        ));

        sb.append("</message>");
        return sb.toString();
    }

    private String formatTimestamp(java.sql.Timestamp ts) {
        if (ts == null) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'") {{
            setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        }}.format(ts);
    }

    // =========================================================================
    // Stream-level handlers
    // =========================================================================

    private void handleStreamOpen(StartElement element, Session session)
            throws IOException {

        //session.setSessionState(SessionState.STREAM_OPENED);
        session.touchActivity();

        Attribute toAttr =
                element.getAttributeByName(new javax.xml.namespace.QName("to"));
        String domain = toAttr != null ? toAttr.getValue() : "localhost";
        // Send stream header response
        session.writeXML(String.format(
                "<?xml version='1.0' encoding='UTF-8'?>" +
                        "<stream:stream " +
                        "from='%s' " +
                        "id='%s' " +
                        "version='1.0' " +
                        "xml:lang='en' " +
                        "xmlns='jabber:client' " +
                        "xmlns:stream='http://etherx.jabber.org/streams'>",
                escapeXml(domain),
                session.getSessionId()
        ));

        // Advertise features appropriate for current state
        session.writeXML(buildStreamFeatures(session));
        logger.info("Stream opened uid=" + session.getSessionId()
                + " domain=" + domain + " state=" + session.getSessionState());
    }

    /**
     * Builds the <stream:features> element based on current session state.
     * <p>
     *   STREAM_OPENED       → STARTTLS (required)
     *   STARTTLS_NEGOTIATED → SASL mechanisms
     *   AUTHENTICATED       → Resource bind (required) + optional features
     */
    private String buildStreamFeatures(Session session) {
        return switch (session.getSessionState()) {
            case STREAM_OPENED ->
                    "<stream:features>" +
                            "<starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'>" +
                            "<required/>" +
                            "</starttls>" +
                            "</stream:features>";

            case STARTTLS_NEGOTIATED ->
                    "<stream:features>" +
                            "<mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>" +
                            "<mechanism>PLAIN</mechanism>" +
                            "</mechanisms>" +
                            "</stream:features>\n";

            /// The server advertises the Stream Management to the client
            /// So, it is now left for the client to determined if he wants
            /// to enable the stream management or not
            ///
            case AUTHENTICATED ->
                    "<stream:features>" +
                            "<bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'>" +
                            "<required/>" +
                            "</bind>" +
                            "<session xmlns='urn:ietf:params:xml:ns:xmpp-session'/>" +
                            "<sm xmlns='urn:xmpp:sm:3' version='3'/>" +
                            "</stream:features>";

            default ->
                    "<stream:features/>";
        };
    }

    private void handleStartTLS(Session session)
            throws StartTLSException, IOException {

        if (session.getSessionState() == SessionState.STARTTLS_NEGOTIATED) {
            session.writeStreamError(
                    StreamException.Condition.POLICY_VIOLATION,
                    "STARTTLS already negotiated"
            );
            throw new StartTLSException("Duplicate STARTTLS");
        }

        // Must write to the PLAIN socket (before upgrade)
        OutputStreamWriter writer = new OutputStreamWriter(
                session.getSocket().getOutputStream(), StandardCharsets.UTF_8);
        writer.write("<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>");
        writer.flush();

        // Break out of the event loop so ConnectionHandler can upgrade the socket
        throw new StartTLSException("Client requested STARTTLS upgrade");
    }

    // =========================================================================
    // Utilities
    // =========================================================================

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

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}