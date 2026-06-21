package streammessenger.mutlidevice;



import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.*;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles multi-device encrypted message routing.
 * <p>
 * WHAT THIS IS:
 * ─────────────
 * When Alice sends a message to Bob who has 3 devices,
 * Alice's client encrypts the message 3 times (once per device key).
 * This handler receives all 3 ciphertexts and routes each one
 * to the correct device.
 * <p>
 * STANZA FORMAT:
 * ──────────────
 * <message id='m1' to='bob@domain.com' type='chat'>
 *   <multi-encrypted xmlns='urn:xmpp:omemo:2'>
 * <p>
 *     <!-- For Bob's phone -->
 *     <device-message device_id='phone-uuid' iv='BASE64_IV'>
 *       BASE64_CIPHERTEXT_FOR_PHONE
 *     </device-message>
 * <p>
 *     <!-- For Bob's laptop -->
 *     <device-message device_id='laptop-uuid' iv='BASE64_IV'>
 *       BASE64_CIPHERTEXT_FOR_LAPTOP
 *     </device-message>
 * <p>
 *     <!-- For Bob's tablet -->
 *     <device-message device_id='tablet-uuid' iv='BASE64_IV'>
 *       BASE64_CIPHERTEXT_FOR_TABLET
 *     </device-message>
 * <p>
 *     <!-- Mime type hint (not sensitive) -->
 *     <meta msg_type='text' mime='text/plain'/>
 * <p>
 *   </multi-encrypted>
 * </message>
 * <p>
 * Server routes each <device-message> to the matching device.
 * If the target device is offline: stores that specific ciphertext.
 * Online devices receive their ciphertext immediately.
 */
public final class MultiDeviceMessageHandler {

    private static final Logger logger =
            Logger.getLogger(MultiDeviceMessageHandler.class.getName());

    private static final String OMEMO_NS = "urn:xmpp:omemo:2";

    private final SessionRegistry registry;
    private final DatabaseManager db;

    public MultiDeviceMessageHandler(SessionRegistry registry,
                                     DatabaseManager db) {
        this.registry = registry;
        this.db       = db;
    }

    /**
     * Processes a multi-device encrypted message stanza.
     *
     * @param element The outer <message> start element
     * @param reader  The XML reader positioned after the element
     * @param sender  The sending session
     */
    public void handle(StartElement element,
                        XMLEventReader reader,
                        Session sender) {

        String messageId    = getAttr(element, "id");
        String to           = getAttr(element, "to");
        String toContactId  = toBareJid(to);

        if (messageId == null) messageId = UUID.randomUUID().toString();
        if (toContactId == null) {
            consumeElement(reader);
            return;
        }

        // Parse all device-specific ciphertexts
        MultiDevicePayload payload = parsePayload(reader);
        if (payload == null || payload.deviceMessages().isEmpty()) {
            return;
        }

        // Route each ciphertext to the correct device
        int delivered = 0;
        int stored    = 0;

        for (DeviceMessage dm : payload.deviceMessages()) {
            String result = routeToDevice(
                    messageId, sender.getContactId(),
                    toContactId, dm, payload.msgType(),
                    payload.mimeType()
            );

            if ("delivered".equals(result)) delivered++;
            else if ("stored".equals(result)) stored++;
        }

        logger.fine("Multi-device message routed: messageId=" + messageId
                + " to=" + toContactId
                + " delivered=" + delivered
                + " stored=" + stored);

        // Send server receipt to sender
        sender.writeXML(String.format(
            "<message id='%s' to='%s'>" +
            "<received xmlns='urn:xmpp:receipts' id='%s'/>" +
            "</message>",
            UUID.randomUUID(),
            escapeXml(sender.getContactId()),
            escapeXml(messageId)
        ));

        // Fan out SENT carbons to sender's other devices
        CarbonManager.getInstance().fanoutSentCarbon(
                buildSingleDeviceStanza(messageId, sender.getContactId(),
                        toContactId, payload),
                sender.getContactId(),
                sender.getSessionId(),
                messageId,
                java.time.Instant.now().toString()
        );
    }

    // =========================================================================
    // Private routing
    // =========================================================================

    /**
     * Routes a single device-specific ciphertext to the target device.
     *
     * @return "delivered" | "stored" | "not_found"
     */
    private String routeToDevice(String messageId,
                                  String fromJid,
                                  String toContactId,
                                  DeviceMessage dm,
                                  String msgType,
                                  String mimeType) {

        // Build stanza for this specific device
        String stanza = String.format(
            "<message id='%s' from='%s' to='%s' type='chat'>" +
            "<device-encrypted xmlns='%s'" +
            " device_id='%s' iv='%s' msg_type='%s'%s>%s" +
            "</device-encrypted>" +
            "<request xmlns='urn:xmpp:receipts'/>" +
            "</message>",
            escapeXml(messageId),
            escapeXml(fromJid),
            escapeXml(toContactId),
            OMEMO_NS,
            escapeXml(dm.deviceId()),
            escapeXml(dm.iv()),
            msgType != null ? escapeXml(msgType) : "text",
            mimeType != null ? " mime='" + escapeXml(mimeType) + "'" : "",
            dm.ciphertext()
        );

        // Find the target device's session
        Optional<Session> targetSession =
                findSessionByDeviceId(toContactId, dm.deviceId());

        if (targetSession.isPresent()
                && targetSession.get().isAuthenticated()) {
            boolean sent = targetSession.get().writeXML(stanza);
            if (sent) return "delivered";
        }

        // Device is offline - store for later delivery
        db.storeEncryptedMessage(
                fromJid, toContactId, messageId,
                msgType != null ? msgType : "text",
                dm.ciphertext(), dm.iv(),
                null, null, mimeType, 0, null
        );
        return "stored";
    }

    /**
     * Finds the active session for a specific device ID.
     * Looks through all sessions for the contactId and matches device_id.
     */
    private Optional<Session> findSessionByDeviceId(String contactId,
                                                      String deviceId) {
        return registry.getSessionsByContactId(contactId)
                .stream()
                .filter(s -> deviceId.equals(s.getDeviceId()))
                .findFirst();
    }

    /**
     * Builds a representative single-device stanza for carbon purposes.
     * Carbon recipients need to see the structure, not a specific ciphertext.
     */
    private String buildSingleDeviceStanza(String messageId,
                                            String fromJid,
                                            String toJid,
                                            MultiDevicePayload payload) {
        // Use the first device message as representative
        DeviceMessage first = payload.deviceMessages().get(0);
        return String.format(
            "<message id='%s' from='%s' to='%s' type='chat'>" +
            "<multi-encrypted xmlns='%s' msg_type='%s' device_count='%d'/>" +
            "</message>",
            escapeXml(messageId),
            escapeXml(fromJid),
            escapeXml(toJid),
            OMEMO_NS,
            payload.msgType(),
            payload.deviceMessages().size()
        );
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private MultiDevicePayload parsePayload(XMLEventReader reader) {
        List<DeviceMessage> deviceMessages = new ArrayList<>();
        String msgType = "text";
        String mimeType = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    if ("device-message".equals(name)
                            && OMEMO_NS.equals(ns)) {
                        String deviceId  = getAttr(se, "device_id");
                        String iv        = getAttr(se, "iv");
                        String ciphertext = readText(reader);
                        depth--;

                        if (deviceId != null && iv != null
                                && ciphertext != null) {
                            deviceMessages.add(new DeviceMessage(
                                    deviceId, iv, ciphertext));
                        }
                    }

                    if ("meta".equals(name) && OMEMO_NS.equals(ns)) {
                        msgType  = getAttr(se, "msg_type");
                        mimeType = getAttr(se, "mime");
                    }
                }

                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("parsePayload error: " + e.getMessage());
            return null;
        }

        return new MultiDevicePayload(deviceMessages, msgType, mimeType);
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

    // =========================================================================
    // Inner types
    // =========================================================================

    private record DeviceMessage(
            String deviceId,
            String iv,
            String ciphertext
    ) {}

    private record MultiDevicePayload(
            List<DeviceMessage> deviceMessages,
            String msgType,
            String mimeType
    ) {}
}