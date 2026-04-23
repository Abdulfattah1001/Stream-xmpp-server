package streammessenger.stanza;


import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;

import streammessenger.session.Session;

/**
 * Common interface for all XMPP stanza handlers.
 * Each handler is stateless and can be shared across threads.
 */
public interface StanzaHandler {
    /**
     * @param element The opening StartElement of the stanza
     * @param reader  Positioned just after the start element
     * @param session The session that sent this stanza
     */
    void handle(StartElement element, XMLEventReader reader, Session session);
}