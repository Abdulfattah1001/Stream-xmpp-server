package streammessenger.stanza;

import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

public class PrivacyHandler implements StanzaHandler{

    private static final Logger logger = Logger.getLogger(PrivacyHandler.class.getName());
    private static final String PRIVACY_NS = "urn:xmpp:custom:privacy:0";
    private final SessionRegistry registry;
    private final DatabaseManager db;


    public PrivacyHandler(DatabaseManager db, SessionRegistry sessionRegistry){
        this.db = db;
        this.registry = sessionRegistry;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {
        String iqId = getAttr(element, "id");
        String iqType = getAttr(element, "type");

        if (!session.isAuthenticated()) {
            sendError(session, iqId, "not-authorized", "auth");
            consumeElement(reader);
            return;
        }

        String userJid = session.getContactId();
        logger.info("User JID: "+ userJid);
        logger.info("CONTACT ID: "+session.getContactId());

        try {
            switch (iqType) {
                case "get" -> handleGet(session, iqId, userJid, reader);
                case "set" -> handleSet(session, iqId, userJid, reader);
                default -> {
                    sendError(session, iqId, "bad-request", "modify");
                    consumeElement(reader);
                }
            }
        } catch (Exception e) {
            logger.warning("Privacy handler error: " + e.getMessage());
            sendError(session, iqId, "internal-server-error", "cancel");
        }
    }

    private void handleGet(Session session, String iqId, String userJid, XMLEventReader reader) {
        consumeElement(reader); // skip body
        Privacy p = db.getPrivacy(userJid).orElseGet(() -> {
            Privacy defaults = Privacy.defaults();
            boolean store = db.insertPrivacy(userJid, defaults);
            return defaults;
        });

        session.writeXML(String.format("""
        <iq type='result' id='%s' to='%s'>
            <query xmlns='%s'>
                <last_seen>%s</last_seen>
                <profile_photo>%s</profile_photo>
                <about>%s</about>
                <read_receipts>%s</read_receipts>
            </query>
        </iq>
        """, escapeXml(iqId), escapeXml(userJid), PRIVACY_NS,
                p.lastSeenVisibility(), p.photoVisibility(),
                p.aboutVisibility(), p.readReceiptsEnabled()));
    }

    private void handleSet(Session session, String iqId, String userJid, XMLEventReader reader)
            throws XMLStreamException {

        // Load current (so we only update fields the client sent)
        Privacy current = db.getPrivacy(userJid).orElse(Privacy.defaults());

        String lastSeen     = current.lastSeenVisibility();
        String profilePhoto = current.photoVisibility();
        String about        = current.aboutVisibility();
        boolean readReceipts = current.readReceiptsEnabled();

        // Parse children of <query>
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                String name = event.asStartElement().getName().getLocalPart();
                String value = readText(reader); // consumes content + end tag

                switch (name) {
                    case "last_seen"     -> lastSeen = validateVisibility(value);
                    case "profile_photo" -> profilePhoto = validateVisibility(value);
                    case "about"         -> about = validateVisibility(value);
                    case "read_receipts" -> readReceipts = Boolean.parseBoolean(value);
                    default -> logger.fine("Ignoring unknown privacy field: " + name);
                }
            } else if (event.isEndElement()) {
                depth--;
            }
        }

        // Persist
        boolean store = db.upsertPrivacy(userJid, new Privacy(lastSeen, profilePhoto, about, readReceipts));

        // ALWAYS respond to set
        session.writeXML(String.format(
                "<iq type='result' id='%s' to='%s'/>",
                escapeXml(iqId), escapeXml(userJid)));

        // Optional: push update to other resources of the same user
        //TODO: pushToOtherResources(userJid, session);
    }

    private String validateVisibility(String v) {
        return switch (v) {
            case "everyone", "contacts", "nobody" -> v;
            default -> throw new IllegalArgumentException("Invalid visibility: " + v);
        };
    }


    // =========================================================================
    // Error responses
    // =========================================================================

    /**
     * Sends an IQ error response.
     *
     * @param session   The session to write to
     * @param iqId      The IQ id to echo (can be null)
     * @param condition The XMPP stanza error condition
     * @param type      The error type: cancel|modify|auth|wait
     */
    private void sendError(Session session, String iqId,
                           String condition, String type) {
        session.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='%s'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
                type,
                condition
        ));
    }



    // =========================================================================
    // Utilities
    // =========================================================================

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;")
                .replace("\"", "&quot;");
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

    private String getAttr(StartElement element, String name) {
        javax.xml.stream.events.Attribute attr =
                element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String readText(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.peek();
                if (event.isCharacters()) {
                    reader.nextEvent();
                    sb.append(event.asCharacters().getData());
                } else break;
            }
            // Consume end element
            if (reader.hasNext() && reader.peek().isEndElement()) {
                reader.nextEvent();
            }
        } catch (XMLStreamException ignored) {}
        return sb.toString().trim();
    }

    public record Privacy(
            String lastSeenVisibility,
            String photoVisibility,
            String aboutVisibility,
            Boolean readReceiptsEnabled
    ){
        public static Privacy defaults() {
            return new Privacy(
                    "contacts",
                    "contacts",
                    "contacts",
                    true);
        }
    }
}