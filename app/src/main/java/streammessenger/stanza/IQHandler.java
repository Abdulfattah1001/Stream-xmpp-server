package streammessenger.stanza;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.namespace.QName;

import java.util.Map;
import java.util.logging.Logger;

import streammessenger.api.CloudinarySlotManager;
import streammessenger.call.CallSignalingHandler;
import streammessenger.config.ServerConfig;
import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.features.CollaborativeNoteHandler;
import streammessenger.roster.RosterItem;
import streammessenger.roster.RosterManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.sync.SyncNode;


import javax.xml.stream.events.Attribute;

/**
 * Routes <iq> stanzas to the correct sub-handler based on child element namespace.
 * <p>
 * Supported namespaces:
 *   urn:ietf:params:xml:ns:xmpp-bind  → ResourceBindHandler
 *   jabber:iq:roster                   → RosterManager
 *   urn:ietf:params:xml:ns:xmpp-ping  → inline ping handler
 *   http://jabber.org/protocol/disco#info → inline disco handler
 * <p>
 * Stateless singleton.
 */

@SuppressWarnings("unused")
public final class IQHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(IQHandler.class.getName());

    private static final String NS_BIND      = "urn:ietf:params:xml:ns:xmpp-bind";
    private static final String NS_ROSTER    = "jabber:iq:roster";
    private static final String PUB_SUB_ROSTER = "http://jabber.org/protocol/pubsub";
    public static final String PROFILE_SYNC_NS = "urn:xmpp:profile-sync:1";

    private static final String CALL_NS = "urn:xmpp:call:0";
    private static final String NOTE_NS = "urn:xmpp:note:0";
    private static final String CRDT_NS = "urn:xmpp:crdt-note:0";

    private static final String UPLOAD_SLOT = "urn:xmpp:http:upload:0";
    private static final String PRIVACY_NS = "urn:xmpp:custom:privacy:0";
    private static final String NS_PING      = "urn:ietf:params:xml:ns:xmpp-ping";
    private static final String NS_DISCO     = "http://jabber.org/protocol/disco#info";
    private static final String NS_SESSION   = "urn:ietf:params:xml:ns:xmpp-session";
    private static final String NS_MUC = "http://jabber.org/protocol/muc";
    private static final String BLOCKING_NS = "urn:xmpp:blocking";
    private final ResourceBindHandler bindHandler;
    private final CallSignalingHandler callSignalingHandler;
    private final RosterManager rosterManager;
    private final PrivacyHandler privacyHandler;
    private final CloudinarySlotManager cloudinarySlotManager;
    private final CollaborativeNoteHandler collaborativeNoteHandler;
    private final CRDTNoteHandler crdtNoteHandler;
    private final PubSubHandler pubSubHandler;
    private final BlockHandler blockHandler;

    public IQHandler(DatabaseManager db, SessionRegistry registry,
                     RosterManager rosterManager, CallSignalingHandler callSignalingHandler,
                     CollaborativeNoteHandler handler,
                     CRDTNoteHandler crdtHandler, ServerConfig config, ConnectionPool pool, SyncNode syncNode) {
        this.bindHandler = new ResourceBindHandler(db, registry, pool);
        this.rosterManager = rosterManager;
        this.callSignalingHandler = callSignalingHandler;
        this.cloudinarySlotManager = new CloudinarySlotManager(config);
        this.privacyHandler = new PrivacyHandler(db, registry);
        this.collaborativeNoteHandler = handler;
        this.crdtNoteHandler = crdtHandler;
        this.pubSubHandler = new PubSubHandler(pool,db, registry, syncNode);
        this.blockHandler = new BlockHandler(db, registry);

    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {

        String id   = getAttr(element, "id");
        String type = getAttr(element, "type");
        String from = getAttr(element, "from");

        if (type == null) {
            sendError(session, id, "bad-request", "modify");
            consumeElement(reader);
            return;
        }

        // Peek at the child element to determine routing
        IQChild child = peekChild(reader);

        if (child == null) {
            // Empty IQ - send error
            sendError(session, id, "feature-not-implemented", "cancel");
            return;
        }
        switch (child.namespace()) {
            case NS_BIND -> {
                // Resource bind - pass the whole IQ to bind handler
                // Re-create a StartElement for it to parse
                bindHandler.handle(element, child.replayReader(reader), session);
            }

            case PROFILE_SYNC_NS -> {
                try{
                    pubSubHandler.handleGet(element, reader, session);
                } catch (XMLStreamException e) {
                    logger.info("Exception occurred: "+e.getMessage());
                }
            }

            case PUB_SUB_ROSTER -> pubSubHandler.handle(element, reader, session);

            case BLOCKING_NS ->  {
                logger.info("The current user " + session.getUid() + " is trying to block a user");
                blockHandler.handle(element, reader, session);
            }

            case NS_ROSTER -> handleRosterIQ(type, id, child, reader, session);

            /*case NOTE_NS -> collaborativeNoteHandler.handle(element, reader, session);

            case CRDT_NS -> {
                crdtNoteHandler.handle(element, reader, session);
            }*/

            case NS_PING -> {
                handlePing(id, from, session);
                consumeElement(reader);
            }

            case NS_DISCO -> {
                handleDiscoInfo(id, from, session);
                consumeElement(reader);
            }

            case NS_SESSION -> {
                // Session establishment - just acknowledge it
                session.writeXML(String.format(
                        "<iq type='result' id='%s'/>",
                        id != null ? escapeXml(id) : ""
                ));
                consumeElement(reader);
            }
            case CALL_NS -> callSignalingHandler.handle(element, reader, session);

            case PRIVACY_NS -> privacyHandler.handle(element, reader, session);

            case UPLOAD_SLOT ->  {
                String contentType = null;
                try{
                    XMLEvent event = reader.nextEvent();
                    if(event.isStartElement()){
                        contentType = event.asStartElement().getAttributeByName(new QName("content-type")).getValue();
                    }
                    //if(event.isEndElement()) {}
                }catch (XMLStreamException ignore){}
                String finalContentType = contentType;
                Thread.ofVirtual().name("upload_slot").start(() -> {
                    Map<String, Object> generatedUploadSlot = cloudinarySlotManager.generateUploadSlot(session.getContactId(), finalContentType);

                    String xml = String.format("""
                                <iq type='result'
                                from='upload.omnyrex.com'
                                to='%s'
                                id='%s'>
                                <slot xmlns='%s'>
                                <put url='%s'>
                                <header name='api_key'>%s</header>
                                <header name='signature'>%s</header>
                                <header name='timestamp'>%s</header>
                                <header name='folder'>%s</header>
                                </put>
                                </slot>
                                </iq>""", session.getJid(), id, UPLOAD_SLOT,
                            generatedUploadSlot.get("upload_url"),
                            generatedUploadSlot.get("api_key"),generatedUploadSlot.get("signature"),
                            generatedUploadSlot.get("timestamp"), generatedUploadSlot.get("folder"));
                    logger.info("Final XML: "+xml);
                    session.writeXML(xml);
                });
                consumeElement(reader);
            }

            case NS_MUC ->  {
                logger.info("Handling Group or room creation");
                consumeElement(reader);
            }

            default -> {
                logger.fine("Unsupported IQ namespace: " + child.namespace() + " type=" + type);
                sendError(session, id, "feature-not-implemented", "cancel");
                consumeElement(reader);
            }
        }
    }

    // =========================================================================
    // Roster IQ routing
    // =========================================================================

    private void handleRosterIQ(String type, String iqId, IQChild child,
                                XMLEventReader reader, Session session) {
        logger.info("Handling roster IQ ....");
        switch (type) {
            case "get" -> {
                // Client wants their contact list
                logger.info("processing roster get request ... ");
                @SuppressWarnings("unused")
                String ver = child.getAttribute("ver"); // roster version (may be null)
                consumeElement(reader); // consume the <query/> element
                // TODO: rosterManager.handleRosterGet(session.getContactId(), iqId, ver, session);
            }

            case "set" -> {
                logger.info("Processing roster set ....");
                // Client is adding/updating/removing a contact
                // Parse the <item> from the reader
                RosterItem item = parseRosterItem(reader);
                if (item == null) {
                    sendError(session, iqId, "bad-request", "modify");
                    return;
                }
                rosterManager.handleRosterSet(item, iqId, session);
            }

            default -> {
                sendError(session, iqId, "bad-request", "modify");
                consumeElement(reader);
            }
        }
    }

    // =========================================================================
    // Inline handlers
    // =========================================================================

    private void handlePing(String id, String from, Session session) {
        session.writeXML(String.format(
                "<iq type='result' id='%s'%s/>",
                id != null ? escapeXml(id) : "",
                from != null ? " to='" + escapeXml(from) + "'" : ""
        ));
        session.touchActivity();
    }

    private void handleDiscoInfo(String id, String from, Session session) {
        logger.info("Handling feature discovery ... ");
        session.writeXML(String.format(
                "<iq type='result' id='%s'%s>" +
                        "<query xmlns='http://jabber.org/protocol/disco#info'>" +
                        "<identity category='server' type='im' name='XMPP Server'/>" +
                        "<feature var='urn:ietf:params:xml:ns:xmpp-tls'/>" +
                        "<feature var='urn:ietf:params:xml:ns:xmpp-sasl'/>" +
                        "<feature var='urn:ietf:params:xml:ns:xmpp-bind'/>" +
                        "<feature var='urn:ietf:params:xml:ns:xmpp-ping'/>" +
                        "<feature var='jabber:iq:roster'/>" +
                        "<feature var='urn:xmpp:sm:3'/>" +
                        "</query></iq>",
                id != null ? escapeXml(id) : "",
                from != null ? " to='" + escapeXml(from) + "'" : ""
        ));
    }

    // =========================================================================
    // Parsing helpers
    // =========================================================================

    /**
     * Peeks at the first child element of the IQ stanza to determine routing.
     * Does NOT consume it from the reader.
     */
    private IQChild peekChild(XMLEventReader reader) {
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.peek();

                if (event.isStartElement()) {
                    StartElement child = event.asStartElement();
                    String ns = child.getName().getNamespaceURI();
                    String local = child.getName().getLocalPart();

                    // Read the ver attribute if present (for roster versioning)
                    Attribute verAttr = child.getAttributeByName(new QName("ver"));
                    String ver = verAttr != null ? verAttr.getValue() : null;

                    return new IQChild(local, ns != null ? ns : "", ver);
                }

                if (event.isEndElement()) {
                    return null; // Empty IQ
                }

                reader.nextEvent(); // Consume whitespace/other
            }
        } catch (XMLStreamException e) {
            logger.warning("Error peeking IQ child: " + e.getMessage());
        }
        return null;
    }

    /**
     * Parses a <item> element from a roster set IQ.
     * <p>
     * <query xmlns='jabber:iq:roster'>
     *   <item jid='bob@domain' name='Bob' subscription='none'>
     *     <group>Friends</group>
     *   </item>
     * </query>
     */
    private RosterItem parseRosterItem(XMLEventReader reader) {
        String jid = null, name = null, subscription = null, ask = null;
        java.util.List<String> groups = new java.util.ArrayList<>();
        boolean inGroup = false;
        StringBuilder groupText = new StringBuilder();

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String local = se.getName().getLocalPart();

                    if ("item".equals(local)) {
                        jid = getAttr(se, "jid");
                        name = getAttr(se, "name");
                        subscription = getAttr(se, "subscription");
                        ask = getAttr(se, "ask");
                    }
                    if ("group".equals(local)) {
                        inGroup = true;
                        groupText.setLength(0);
                    }
                }

                if (event.isCharacters() && inGroup) {
                    groupText.append(event.asCharacters().getData());
                }

                if (event.isEndElement()) {
                    depth--;
                    String local = event.asEndElement().getName().getLocalPart();
                    if ("group".equals(local)) {
                        inGroup = false;
                        String group = groupText.toString().trim();
                        if (!group.isEmpty()) groups.add(group);
                    }
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing roster item: " + e.getMessage());
            return null;
        }

        if (jid == null || jid.isBlank()) return null;

        return new RosterItem(
                jid, name,
                subscription != null ? subscription : "none",
                ask,
                groups
        );
    }

    private void sendError(Session session, String id, String condition, String type) {
        session.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='%s'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></iq>",
                id != null ? " id='" + escapeXml(id) + "'" : "",
                type,
                condition
        ));
    }

    private String getAttr(StartElement element, String name) {
        Attribute attr = element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
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

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Represents a peeked-at child element of an IQ stanza.
     * Carries enough information to route the IQ without fully consuming it.
     */
    private record IQChild(String localName, String namespace, String getAttribute) {
        String getAttribute(String name) {
            // Only 'ver' is pre-read during peek
            return "ver".equals(name) ? getAttribute : null;
        }

        /**
         * Returns a reader that replays this child element.
         * Used when we need to pass the full IQ body to a sub-handler.
         * In practice we pass the live reader since we only peeked.
         */
        XMLEventReader replayReader(XMLEventReader original) {
            return original;
        }
    }
}
