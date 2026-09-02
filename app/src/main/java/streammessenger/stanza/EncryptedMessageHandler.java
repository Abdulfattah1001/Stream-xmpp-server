package streammessenger.stanza;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.metrics.ServerMetrics;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles <message> stanzas with full E2E encryption support.
 * <p>
 * The server is a BLIND ROUTER for encrypted messages.
 * It reads only the routing attributes (from, to, id, type)
 * and stores/forwards the ciphertext without decrypting.
 * <p>
 * Supported message types:
 *   text     → encrypted body text
 *   image    → encrypted image key + media storage reference
 *   video    → encrypted video key + media storage reference
 *   audio    → encrypted audio key + media storage reference
 *   file     → encrypted file key + media storage reference
 *   location → encrypted coordinates
 *   contact  → encrypted contact vCard
 * <p>
 * Custom namespace: urn:xmpp:e2ee:0
 * <p>
 * Example stanza (text message):
 *   <message id='m1' to='u_abc123@domain.com' type='chat'>
 *     <encrypted xmlns='urn:xmpp:e2ee:0'
 *                msg_type='text'
 *                iv='BASE64_IV'>
 *       BASE64_CIPHERTEXT
 *     </encrypted>
 *   </message>
 * <p>
 * Example stanza (image message):
 *   <message id='m2' to='u_abc123@domain.com' type='chat'>
 *     <encrypted xmlns='urn:xmpp:e2ee:0'
 *                msg_type='image'
 *                iv='BASE64_IV'
 *                storage_key='media/abc123.enc'
 *                mime='image/jpeg'
 *                size='204800'>
 *       BASE64_ENCRYPTED_MEDIA_KEY
 *     </encrypted>
 *     <reply-to xmlns='urn:xmpp:e2ee:0' id='m0'/>
 *   </message>
 * <p>
 * Delivery receipts (sent back to sender):
 *   <message id='receipt-uuid' to='sender@domain.com'>
 *     <received xmlns='urn:xmpp:receipts' id='m1'/>
 *   </message>
 * <p>
 * Read receipts:
 *   <message id='read-uuid' to='sender@domain.com'>
 *     <displayed xmlns='urn:xmpp:receipts' id='m1'/>
 *   </message>
 */
public final class EncryptedMessageHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(EncryptedMessageHandler.class.getName());

    private static final String E2EE_NS     = "urn:xmpp:e2ee:0";
    private static final String RECEIPTS_NS = "urn:xmpp:receipts";
    private static final String SERVER_RECEIPT_NS = "urn:xmpp:server:receipts";
    private static final String CHAT_NS     = "http://jabber.org/protocol/chatstates";

    private final SessionRegistry registry;
    private final DatabaseManager db;
    private final ServerMetrics metrics;
    private final ReactionHandler reactionHandler;
    private final CRDTNoteHandler crdtNoteHandler;

    public EncryptedMessageHandler(SessionRegistry registry,
                          DatabaseManager db,
                          ServerMetrics metrics, ReactionHandler reactionHandler, CRDTNoteHandler crdtNoteHandler) {
        this.registry = registry;
        this.db       = db;
        this.metrics  = metrics;
        this.reactionHandler = reactionHandler;
        this.crdtNoteHandler = crdtNoteHandler;
    }

    @Override
    public void handle(StartElement element,
                       XMLEventReader reader,
                       Session session) {
        if (!session.isAuthenticated()) {
            logger.info("Unauthenticated message from uid=" + session.getSessionId());
            consumeElement(reader);
            return;
        }

        String id   = getAttr(element, "id");
        String to   = getAttr(element, "to");
        String type = getAttr(element, "type");

        if (id == null) id = UUID.randomUUID().toString();

        if (to == null || to.isBlank()) {
            consumeElement(reader);
            return;
        }


        try{
            XMLEvent event = reader.peek();
            if(event.isStartElement()){
                StartElement sE = event.asStartElement();
                String name = sE.getName().getLocalPart();
                if(name.equals("reactions")) reactionHandler.handle(sE, reader, session);
                if(name.equals("crdt-op")) crdtNoteHandler.handle(sE, reader, session);
            }
        } catch (XMLStreamException e) {
            logger.info("Error peeking <message> stanza: "+e.getMessage());
        }

        // Strip resource - route to bare JID
        String toContactId = bareJid(to); // assuming it comes in the format of u_wbcwvvc@server_name.com/mobile
        // Parse the message content
        ParsedMessage parsed = parseMessageContent(reader, id);

        // Update the last seen status of the sender
        session.touchActivity();


        // Route based on content type
        if (parsed.isReceiptOnly()) {
            // Delivery/read receipt - route directly
            routeReceipt(parsed, session, toContactId);
            return;
        }



        if (parsed.isChatStateOnly()) {
            // Typing indicator - route directly, never store
            routeChatState(parsed, session, toContactId, type);
            return;
        }

        if (parsed.encryptedContent() == null && parsed.mediaUrl == null) {
            // No encrypted content and not a receipt/chat state
            // Reject - we require E2E encryption
            sendNotAcceptableError(session, id);
            consumeElement(reader);
            return;
        }

        // Route or store the encrypted message
        routeOrStoreMessage(parsed, session, toContactId, type, id);
    }

    // =========================================================================
    // Routing
    // =========================================================================

    /**
     * Attempts to deliver the message to the recipient.
     * If offline: stores in database for later delivery.
     * Either way: sends delivery receipt back to sender.
     */
    private void routeOrStoreMessage(ParsedMessage parsed,
                                     Session sender,
                                     String toContactId,
                                     String type,
                                     String messageId) {
        // Check the message keys
        String identityKey = parsed.identityKey();
        Optional<String> key = db.getIdentityKey(toContactId);
        if(key.isPresent() &&!key.get().equals(identityKey)) {
            sender.writeXML(String.format("<message type='INVALID_KEY' id='%s' from='%s'> </message>", messageId, toContactId));
            return;
        }
        // Build the full stanza XML to forward/store
        String stanzaXml = buildEncryptedStanza(
                messageId, sender.getUid()/* sender.getContactId() */, toContactId,
                type, parsed
        );

        boolean delivered = false;

        // Try online delivery first
        java.util.Optional<Session> recipientSession = registry.getByContactId(toContactId);

        if (recipientSession.isPresent() && recipientSession.get().isAuthenticated()) {
            delivered = recipientSession.get().writeXML(stanzaXml);
            if(delivered) logger.info("Message sent"); else logger.info("Message not sent");
        } else {}

        if (delivered) {
            metrics.messageSent();
        } else {
            // Recipient offline - store encrypted ciphertext
            if (db.contactExists(toContactId)) {
                boolean store = db.storeEncryptedMessage(
                        sender.getUid(),
                        toContactId,
                        messageId,
                        parsed.msgType() != null ? parsed.msgType() : "text",
                        parsed.encryptedContent(),
                        parsed.iv(),
                        parsed.mediaStorageKey(),
                        parsed.encryptedMetadata(),
                        parsed.mimeType(),
                        parsed.fileSizeBytes(),
                        parsed.replyToId()
                );

                if(!store){
                    logger.info("Error storing the message for offline");
                }

                metrics.messageStoredOffline();
            } else {
                // Recipient doesn't exist
                sendItemNotFoundError(sender, messageId, toContactId);
                return;
            }
        }

        // Always send server-level receipt to sender
        // (confirms the server received it, not that recipient did)
        sendServerReceipt(sender, messageId);
    }



    /**
     * Routes a delivery/read receipt back to the original sender.
     * Receipts should be stored in the database also
     */
    private void routeReceipt(ParsedMessage parsed,
                              Session sender,
                              String toContactId) {
        logger.info("The sender of the receipt is: "+sender.getContactId()+" :The receiver is: "+toContactId);
        String receiptXml = String.format(
                "<message id='%s' from='%s' to='%s'>" +
                        "<%s xmlns='%s' id='%s'/>" +
                        "</message>",
                UUID.randomUUID(),
                escapeXml(sender.getContactId()),
                escapeXml(toContactId),
                parsed.receiptType(), // "received" or "displayed"
                RECEIPTS_NS,
                escapeXml(parsed.receiptId())
        );

        registry.getByContactId(toContactId.split("@")[0]+"@localhost").ifPresentOrElse(s -> {
            if (s.isAuthenticated()) {
                boolean sent =  s.writeXML(receiptXml);
                // If the user is online, but couldn't sent
                if(!sent){
                    db.storeReceipt(sender.getUid(), toContactId, parsed.receiptId, parsed.receiptType);
                }

                /*if ("displayed".equals(parsed.receiptType())) {
                    db.markMessageRead(parsed.receiptId());
                } else {
                    db.markMessageDelivered(parsed.receiptId());
                }*/
            }
        }, () -> {
            // user offline

            db.storeReceipt(
                    sender.getContactId(),
                    toContactId,
                    parsed.receiptId,
                    parsed.receiptType
            );
        });

    }

    /**
     * Routes a chat state notification (typing indicator).
     * Never stored - purely ephemeral.
     */
    private void routeChatState(ParsedMessage parsed,
                                Session sender,
                                String toContactId,
                                String type) {
        String stanza = String.format(
                "<message from='%s' to='%s' type='%s'>" +
                        "<%s xmlns='%s'/>" +
                    "</message>",
                escapeXml(sender.getContactId()),
                escapeXml(toContactId),
                escapeXml(type != null ? type : "chat"),
                parsed.chatState(),
                CHAT_NS
        );

        registry.getByContactId(toContactId).ifPresent(s -> {
            if (s.isAuthenticated()) s.writeXML(stanza);
        });
    }

    // =========================================================================
    // Stanza building
    // =========================================================================

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
        if(parsed.mediaUrl() != null){
            sb.append(String.format(
                    " media_url='%s'",
                    escapeXml(parsed.mediaUrl())
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

    // =========================================================================
    // Parsing
    // =========================================================================

    /**
     * Parses all relevant child elements from the <message> stanza.
     * <p>
     * The server reads ONLY routing/metadata attributes.
     * The encrypted ciphertext is read as an opaque string.
     */
    @SuppressWarnings("unused")
    private ParsedMessage parseMessageContent(XMLEventReader reader,
                                              String messageId) {
        String encryptedContent  = null;
        String iv                = null;
        String msgType           = "text";
        String mediaStorageKey   = null;
        String identityKey       = null;
        String mediaUrl          = null;
        String encryptedMetadata = null;
        String metaIv            = null;
        String mimeType          = null;
        long   fileSizeBytes     = 0;
        String replyToId         = null;
        String receiptType       = null;
        String receiptId         = null;
        String chatState         = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement child = event.asStartElement();
                    String name = child.getName().getLocalPart();
                    String ns   = child.getName().getNamespaceURI();
                    if (ns == null) ns = "";

                    // E2EE encrypted payload
                    if ("encrypted".equals(name) && E2EE_NS.equals(ns)) {
                        iv              = getAttr(child, "iv");
                        msgType         = getAttr(child, "msg_type");
                        mediaStorageKey = getAttr(child, "storage_key");
                        mediaUrl        = getAttr(child, "media_url");
                        mimeType        = getAttr(child, "mime");
                        String size     = getAttr(child, "size");
                        identityKey     = getAttr(child, "identity_key");

                        if (size != null) {
                            try { fileSizeBytes = Long.parseLong(size); }
                            catch (NumberFormatException ignored) {}
                        }

                        encryptedContent = readText(reader);
                        depth--; // readText consumed the end element
                    }

                    // Encrypted metadata
                    else if ("meta".equals(name) && E2EE_NS.equals(ns)) {
                        metaIv           = getAttr(child, "iv");
                        encryptedMetadata = readText(reader);
                        depth--;
                    }

                    // Reply reference
                    else if ("reply-to".equals(name) && E2EE_NS.equals(ns)) {
                        replyToId = getAttr(child, "id");
                    }

                    // Delivery receipt
                    else if ("received".equals(name) && RECEIPTS_NS.equals(ns)) {
                        receiptType = "received";
                        receiptId   = getAttr(child, "id");
                    }

                    // Read receipt
                    else if ("displayed".equals(name) && RECEIPTS_NS.equals(ns)) {
                        receiptType = "displayed";
                        receiptId   = getAttr(child, "id");
                    }

                    // Chat state notifications
                    else if (CHAT_NS.equals(ns)) {
                        chatState = name; // composing|paused|active|inactive|gone
                    }
                }

                if (event.isEndElement()) {
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing message: " + e.getMessage());
        }

        return new ParsedMessage(
                encryptedContent, iv, msgType,
                mediaStorageKey, identityKey, mediaUrl,encryptedMetadata, metaIv,
                mimeType, fileSizeBytes, replyToId,
                receiptType, receiptId, chatState
        );
    }

    // =========================================================================
    // Error responses
    // =========================================================================

    /**
     * Server receipt - confirms server received the message.
     * Sent even for offline  messages (confirms storage). when the receiver is offline
     */
    private void sendServerReceipt(Session sender, String messageId) {
        boolean sent = sender.writeXML(String.format(
                "<message id='%s' to='%s'>" +
                        "<server-received xmlns='%s' id='%s'/>" +
                    "</message>",
                UUID.randomUUID(),
                escapeXml(sender.getContactId()),
                SERVER_RECEIPT_NS,
                escapeXml(messageId)
        ));

        if(!sent){
            db.storeReceipt("server", sender.getContactId(), messageId, "server-received");
        }
    }


    @SuppressWarnings("unused")
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

    private void sendNotAcceptableError(Session sender, String messageId) {
        sender.writeXML(String.format(
                "<message id='%s' type='error'>" +
                        "<error type='modify'>" +
                        "<not-acceptable " +
                        "xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "<text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>" +
                        "Messages must be end-to-end encrypted" +
                        "</text>" +
                        "</error></message>",
                escapeXml(messageId)
        ));
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

    private String bareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
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
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner type
    // =========================================================================

    private record ParsedMessage(
            String encryptedContent,
            String iv,
            String msgType,
            String mediaStorageKey,
            String identityKey,
            String mediaUrl,
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
}