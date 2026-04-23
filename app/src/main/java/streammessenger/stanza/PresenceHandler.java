package streammessenger.stanza;


import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.namespace.QName;
import java.util.logging.Logger;

import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles <presence> stanzas.
 * Broadcasts presence updates to all currently connected sessions.
 * 
 * Stateless singleton.
 */
public final class PresenceHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(PresenceHandler.class.getName());
    private final SessionRegistry registry;

    public PresenceHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {
        if (!session.isAuthenticated()) {
            consumeElement(reader);
            return;
        }

        String type = getAttr(element, "type");
        String show = extractChildText(reader, "show");

        String presenceXml = buildPresenceStanza(session.getJid(), type, show);

        // Broadcast to all authenticated sessions (excluding sender)
        int broadcast = 0;
        for (Session other : registry.getAllSessions()) {
            if (!other.getUid().equals(session.getUid()) && other.isAuthenticated()) {
                other.writeXML(presenceXml);
                broadcast++;
            }
        }

        logger.fine("Presence broadcast from " + session.getJid()
                + " to " + broadcast + " sessions");
    }

    private String buildPresenceStanza(String from, String type, String show) {
        StringBuilder sb = new StringBuilder("<presence from='")
                .append(from != null ? from : "unknown").append("'");
        if (type != null) sb.append(" type='").append(type).append("'");
        sb.append(">");
        if (show != null) sb.append("<show>").append(show).append("</show>");
        sb.append("</presence>");
        return sb.toString();
    }

    private String getAttr(StartElement element, String name) {
        javax.xml.stream.events.Attribute attr = element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private String extractChildText(XMLEventReader reader, String childName) {
        String value = null;
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()
                        && event.asStartElement().getName().getLocalPart().equals(childName)) {
                    if (reader.hasNext()) {
                        XMLEvent text = reader.nextEvent();
                        if (text.isCharacters()) value = text.asCharacters().getData();
                    }
                }
                if (event.isEndElement()
                        && event.asEndElement().getName().getLocalPart().equals("presence")) {
                    break;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing presence: " + e.getMessage());
        }
        return value;
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
}