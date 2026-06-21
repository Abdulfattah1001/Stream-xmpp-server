package streammessenger.stanza;


import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.namespace.QName;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.metrics.ServerMetrics;
import streammessenger.mutlidevice.CarbonManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * @author abdulfattah
 * @date Thu Apr 23 10:10PM
 * Handles <message> stanzas.
 * <p>
 * Routing logic:
 * 1. If recipient is online -> deliver directly via their session's writeXML
 * 2. If recipient is offline -> store in database for later delivery
 * 3. If recipient doesn't exist -> send error back to sender [This shouldn't happened on a normal ground]
 * <p>
 * Stateless - safe to share as a singleton.
 */
public final class MessageHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(MessageHandler.class.getName());

    private final SessionRegistry registry;
    private final DatabaseManager db;
    private final ServerMetrics metrics;

    private static final String E2EE_NS     = "urn:xmpp:e2ee:0";
    private static final String RECEIPTS_NS = "urn:xmpp:receipts"; //For user request receipt which would be set at the sender side equivalent to WhatsApp receipt urn off / or
    private static final String CHAT_NS     = "http://jabber.org/protocol/chatstates";

    public MessageHandler(SessionRegistry registry, DatabaseManager db, ServerMetrics metrics) {
        this.registry = registry;
        this.db = db;
        this.metrics = metrics;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session senderSession) {
        if (!senderSession.isAuthenticated()) {
            logger.warning("Unauthenticated message attempt from uid=" + senderSession.getSessionId());
            senderSession.writeXML(buildNotAuthorizedError());
            consumeElement(reader);
            return;
        }

        String to = getAttr(element, "to");
        String type = getAttr(element, "type"); //chat | media | group
        String stanzaId = getAttr(element, "id");
        if (stanzaId == null) stanzaId = UUID.randomUUID().toString();

        if (to == null || to.isBlank()) {
            senderSession.writeXML(buildBadRequestError(stanzaId));
            consumeElement(reader);
            return;
        }

        senderSession.touchActivity();

        // Extract bare JID from to (strip resource if present)
        logger.info("Receiver is: "+to);
        String toContactId = to.contains("/") ? to.substring(0, to.indexOf('/')) : to;

        String body = extractBody(reader);
        String fromJid = senderSession.getJid() != null
                ? senderSession.getJid()
                : senderSession.getContactId();

        // Build the full stanza to deliver
        String stanza = buildMessageStanza(stanzaId, fromJid, to, type, body);

        Optional<Session> recipientSession = registry.getByContactId(toContactId);

        if (recipientSession.isPresent() && recipientSession.get().isAuthenticated()) {
            // Online delivery
            boolean delivered = recipientSession.get().writeXML(stanza);
            if (delivered) {
                metrics.messageSent();
                logger.fine("Message delivered online: " + fromJid + " -> " + toContactId);
            } else {
                // Write failed (connection dropped between check and write)
                storeOffline(fromJid, toContactId, body, stanzaId);
            }
        } else {
            // Offline storage
            if (db.contactExists(toContactId)) {
                storeOffline(fromJid, toContactId, body, stanzaId);
            } else {
                // Recipient doesn't exist
                logger.info("Receiver doesn't exist...");
                senderSession.writeXML(buildRecipientNotFoundError(stanzaId, to));
            }
        }
    }

    private void storeOffline(String fromJid, String toContactId, String body, String stanzaId) {
        boolean stored = db.storeOfflineMessage(fromJid, toContactId, body, stanzaId);
        if (stored) {
            metrics.messageStoredOffline();
            logger.fine("Message stored offline: " + fromJid + " -> " + toContactId);
        } else {
            logger.warning("Failed to store offline message: " + fromJid + " -> " + toContactId);
        }
    }

    private String extractBody(XMLEventReader reader) {
        StringBuilder body = new StringBuilder();
        boolean inBody = false;

        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()
                        && event.asStartElement().getName().getLocalPart().equals("body")) {
                    inBody = true;
                    continue;
                }
                if (event.isEndElement()
                        && event.asEndElement().getName().getLocalPart().equals("body")) {
                    inBody = false;
                    continue;
                }
                if (event.isEndElement()
                        && event.asEndElement().getName().getLocalPart().equals("message")) {
                    break;
                }
                if (inBody && event.isCharacters()) {
                    body.append(event.asCharacters().getData());
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing message body: " + e.getMessage());
        }

        return body.toString();
    }

    private String buildMessageStanza(String id, String from, String to,
                                       String type, String body) {
        return String.format(
            "<message id='%s' from='%s' to='%s' type='%s'>" +
            "<body>%s</body>" +
            "</message>",
            escapeXml(id),
            escapeXml(from),
            escapeXml(to),
            type != null ? escapeXml(type) : "chat",
            escapeXml(body)
        );
    }

    private String buildNotAuthorizedError() {
        return "<message type='error'>" +
               "<error type='auth'>" +
               "<not-authorized xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
               "</error></message>";
    }

    private String buildBadRequestError(String id) {
        return "<message id='" + id + "' type='error'>" +
               "<error type='modify'>" +
               "<bad-request xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
               "</error></message>";
    }

    private String buildRecipientNotFoundError(String id, String to) {
        return "<message id='" + id + "' to='" + escapeXml(to) + "' type='error'>" +
               "<error type='cancel'>" +
               "<item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
               "</error></message>";
    }

    private String getAttr(StartElement element, String name) {
        javax.xml.stream.events.Attribute attr = element.getAttributeByName(new QName(name));
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

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("'", "&apos;")
                    .replace("\"", "&quot;");
    }


    private void routeOrStoreMessageOld(ParsedMessage parsed,
                                     Session sender,
                                     String toContactId,
                                     String type,
                                     String messageId) {

        String stanzaXml = buildEncryptedStanza(
                messageId, sender.getContactId(), toContactId,
                type, parsed
        );

        boolean delivered = false;
        String  deliveredToUid = null;

        // Try online delivery
        java.util.Optional<Session> recipientSession =
                registry.getByContactId(toContactId);

        if (recipientSession.isPresent()
                && recipientSession.get().isAuthenticated()) {

            delivered = recipientSession.get().writeXML(stanzaXml);
            if (delivered) {
                deliveredToUid = recipientSession.get().getSessionId();
            }
        }

        if (delivered) {
            metrics.messageSent();
            db.markMessageDelivered(messageId);
        } else {
            if (db.contactExists(toContactId)) {
                db.storeEncryptedMessage(
                        sender.getContactId(), toContactId,
                        messageId,
                        parsed.msgType() != null ? parsed.msgType() : "text",
                        parsed.encryptedContent(), parsed.iv(),
                        parsed.mediaStorageKey(),
                        parsed.encryptedMetadata(),
                        parsed.mimeType(),
                        parsed.fileSizeBytes(),
                        parsed.replyToId()
                );
                metrics.messageStoredOffline();
            } else {
                sendItemNotFoundError(sender, messageId, toContactId);
                return;
            }
        }

        // Server receipt to sender
        sendServerReceipt(sender, messageId);

        // ─── CARBON FANOUT ────────────────────────────────────────────────
        // Fan out to other devices of both sender and recipient
        dispatchCarbons(stanzaXml, messageId, sender,
                toContactId, deliveredToUid);
    }

    private void routeOrStoreMessage(MessageHandler.ParsedMessage parsed,
                                     Session sender,
                                     String toContactId,
                                     String type,
                                     String messageId) {

        String stanzaXml = buildEncryptedStanza(messageId, sender.getContactId(), toContactId, type, parsed);
        boolean delivered = false;
        String deliveredToUid = null;


        // Try online delivery
        java.util.Optional<Session> recipientSession =
                registry.getByContactId(toContactId);

        if (recipientSession.isPresent()
                && recipientSession.get().isAuthenticated()) {

            delivered = recipientSession.get().writeXML(stanzaXml);
            if (delivered) {
                deliveredToUid = recipientSession.get().getSessionId();
            }
        }

        if (delivered) {
            metrics.messageSent();
            db.markMessageDelivered(messageId);
        } else {
            // ... offline storage ...
            if (db.contactExists(toContactId)) {
                db.storeEncryptedMessage(
                        sender.getContactId(), toContactId,
                        messageId,
                        parsed.msgType() != null ? parsed.msgType() : "text",
                        parsed.encryptedContent(), parsed.iv(),
                        parsed.mediaStorageKey(),
                        parsed.encryptedMetadata(),
                        parsed.mimeType(),
                        parsed.fileSizeBytes(),
                        parsed.replyToId()
                );
                metrics.messageStoredOffline();
            } else {
                sendItemNotFoundError(sender, messageId, toContactId);
                return;
            }
        }

        //Server receipt to sender
        sendServerReceipt(sender, messageId);

        // ─── CARBON FANOUT ───
        CarbonManager carbons = CarbonManager.getInstance();
        String timestamp = java.time.Instant.now().toString();

        // Sent carbons → sender's other devices
        if (carbons.hasCarbonsEnabled(sender)) {
            carbons.fanoutSentCarbon(
                    stanzaXml,
                    sender.getContactId(),
                    sender.getSessionId(),
                    messageId,
                    timestamp
            );
        }

        // Received carbons → recipient's other devices (if delivered online)
        if (deliveredToUid != null) {
            carbons.fanoutReceivedCarbon(
                    stanzaXml,
                    toContactId,
                    deliveredToUid,
                    messageId,
                    timestamp
            );
        }
    }

    /**
     * Server receipt - confirms server received the message.
     * Sent even for offline messages (confirms storage).
     */
    private void sendServerReceipt(Session sender, String messageId) {
        logger.info("Sending server receipt to the sender....");
        sender.writeXML(String.format(
                "<message id='%s' to='%s'>" +
                        "<received xmlns='%s' id='%s'/>" +
                        "</message>",
                UUID.randomUUID(),
                escapeXml(sender.getContactId()),
                RECEIPTS_NS,
                escapeXml(messageId)
        ));
    }

    /**
     * Fan out the message stanza to other sender and recipient devices
     * @param stanzaXml The message stanza to be fan out
     * @param messageId The message Id
     * @param sender The sender session
     * @param receiverId The receiver session
     * @param deliveredToUid The deliveredtoUid
     */
    private void dispatchCarbons(String stanzaXml, String messageId, Session sender,
                                 String receiverId, String deliveredToUid){
        //TODO:
    }

    private record ParsedMessage(
            String encryptedContent,
            String iv,
            String msgType,
            String mediaStorageKey,
            String encryptedMetadata,
            String metaIv,
            String mimeType,
            long fileSizeBytes,
            String replyToId,
            String receiptType,
            String receiptId,
            String chatState
    ) {
        boolean isReceiptOnly() {
            return receiptType != null
                    && encryptedContent == null
                    && chatState == null;
        }

        boolean isChatStateOnly() {
            return chatState != null
                    && encryptedContent == null
                    && receiptType == null;
        }
    }

    private void sendItemNotFoundError(Session sender,
                                       String messageId,
                                       String to) {
        sender.writeXML(String.format(
                "<message id='%s' type='error'>" +
                        "<error type='cancel'>" +
                        "<item-not-found " +
                        "xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></message>",
                escapeXml(messageId)
        ));
    }

    /**
     * Builds the full encrypted message stanza for routing/storage.
     * The server constructs this to add the 'from' attribute
     * (client only sets 'to', server adds 'from' for security).
     */
    private String buildEncryptedStanza(String messageId,
                                        String fromJid,
                                        String toJid,
                                        String type,
                                        ParsedMessage parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "<message id='%s' from='%s' to='%s' type='%s'>",
                escapeXml(messageId),
                escapeXml(fromJid),
                escapeXml(toJid),
                escapeXml(type != null ? type : "chat")
        ));

        // Encrypted payload
        sb.append(String.format(
                "<encrypted xmlns='%s' msg_type='%s' iv='%s'",
                E2EE_NS,
                escapeXml(parsed.msgType()),
                escapeXml(parsed.iv())
        ));

        // Media attributes (not sensitive - hints for UI)
        if (parsed.mediaStorageKey() != null) {
            sb.append(String.format(
                    " storage_key='%s'",
                    escapeXml(parsed.mediaStorageKey())
            ));
        }
        if (parsed.mimeType() != null) {
            sb.append(String.format(
                    " mime='%s'", escapeXml(parsed.mimeType())));
        }
        if (parsed.fileSizeBytes() > 0) {
            sb.append(String.format(
                    " size='%d'", parsed.fileSizeBytes()));
        }

        sb.append(">");
        sb.append(parsed.encryptedContent());
        sb.append("</encrypted>");

        // Encrypted metadata (dimensions, duration, thumbnail key)
        if (parsed.encryptedMetadata() != null) {
            sb.append(String.format(
                    "<meta xmlns='%s' iv='%s'>%s</meta>",
                    E2EE_NS,
                    escapeXml(parsed.metaIv()),
                    escapeXml(parsed.encryptedMetadata())
            ));
        }

        // Reply reference
        if (parsed.replyToId() != null) {
            sb.append(String.format(
                    "<reply-to xmlns='%s' id='%s'/>",
                    E2EE_NS, escapeXml(parsed.replyToId())
            ));
        }

        // Request delivery receipt
        sb.append(String.format(
                "<request xmlns='%s'/>", RECEIPTS_NS));
        sb.append("</message>");
        return sb.toString();
    }
}


/**
 * <message to='recipient_user@yourdomain.com' type='chat' id='msg_media_9921'>
 *   <!-- Fallback body text for ancient clients that don't support inline media previews -->
 *   <body>Sent a video preview.</body>
 *
 *   <!-- The Metadata Payload Container -->
 *   <media-sharing xmlns='urn:xmpp:sims:1'>
 *     <file xmlns='urn:xmpp:file:metadata:0'>
 *       <!-- Core URL Source Route -->
 *       <url>https://res.cloudinary.com/dhsnoieuh/video/upload/v1779285850/chat_media/user_abc/video_101.mp4</url>
 *
 *       <!-- Crucial UX Metadata -->
 *       <name>video_101.mp4</name>
 *       <size>4521090</size> <!-- Size in Bytes (Approx 4.3 MB) -->
 *       <media-type>video/mp4</media-type>
 *
 *       <!-- Specialized Visual Dimensions & Playback Attributes -->
 *       <dimensions>1080x1920</dimensions> <!-- Width x Height for Aspect Ratio layout parsing -->
 *       <duration>14</duration> <!-- Duration in Seconds if it's a video -->
 *
 *       <!-- WhatsApp Style Blur Placeholder -->
 *       <blurhash>L6PZg:e._3NX_4ofE1Rj%MWB4mRj</blurhash>
 *     </file>
 *   </media-sharing>
 * </message>
 */