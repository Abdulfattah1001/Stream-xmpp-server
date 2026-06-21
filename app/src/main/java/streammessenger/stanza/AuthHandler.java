package streammessenger.stanza;


import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.XMLEvent;
import javax.xml.stream.events.StartElement;
import java.util.logging.Logger;

import streammessenger.auth.AuthManager;
import streammessenger.exception.AuthenticationException;
import streammessenger.exception.StreamException;
import streammessenger.session.Session;

/**
 * Handles <auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl'> stanzas.
 * Stateless - safe to share as a singleton across connections.
 */
public final class AuthHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(AuthHandler.class.getName());
    private static final String SASL_NS = "urn:ietf:params:xml:ns:xmpp-sasl";

    private final AuthManager authManager;

    public AuthHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {
        // Extract mechanism attribute
        Attribute mechAttr = element.getAttributeByName(
                new javax.xml.namespace.QName("mechanism"));

        if (mechAttr == null) {
            session.writeStreamError(StreamException.Condition.BAD_FORMAT,
                    "Missing mechanism attribute");
            return;
        }

        String mechanism = mechAttr.getValue();
        String payload = extractPayload(reader);

        try {
            //TODO: authManager.authenticate(mechanism, payload, session);
            //
            authManager.authenticateUserToken(mechanism, payload, session);

            // RFC 6120: On success, send <success> and the client must
            // open a new stream
            session.writeXML("<success xmlns='" + SASL_NS + "'/>");

        } catch (AuthenticationException e) {
            logger.warning("Auth failed uid=" + session.getSessionId()
                    + " reason=" + e.getReason() + ": " + e.getMessage());
            String failureCondition = mapFailureCondition(e.getReason());
            session.writeXML(
                "<failure xmlns='" + SASL_NS + "'>" +
                "<" + failureCondition + "/>" +
                "</failure>"
            );
        }
    }

    private String extractPayload(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isCharacters()) {
                    Characters chars = event.asCharacters();
                    if (!chars.isWhiteSpace()) {
                        sb.append(chars.getData());
                    }
                }
                if (event.isEndElement()) break;
            }
        } catch (XMLStreamException e) {
            logger.warning("Error reading auth payload: " + e.getMessage());
        }
        return sb.toString().trim();
    }

    private String mapFailureCondition(AuthenticationException.Reason reason) {
        return switch (reason) {
            case INVALID_CREDENTIALS -> "not-authorized";
            case ACCOUNT_DISABLED -> "account-disabled";
            case MECHANISM_NOT_SUPPORTED -> "invalid-mechanism";
            case MALFORMED_REQUEST -> "malformed-request";
        };
    }
}