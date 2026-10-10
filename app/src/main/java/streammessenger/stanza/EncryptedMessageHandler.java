package streammessenger.stanza;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import streammessenger.call.CallSignalingHandler;
import streammessenger.db.DatabaseManager;
import streammessenger.metrics.ServerMetrics;
import streammessenger.push.PushNotificationService;
import streammessenger.repository.MessageRepository;
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
 *
 *   <p>
 * Destructive messages types
 *      <message id='read-uuid' to='sender@domain.com'>
 *         <descructive>message content</descructive>
 *      </message>
 * <p>
 * Last message correct
 *      <message id='read-uuid' to='sender@domain.com'>
 *          <encrypted>
 *              <body></body>
 *          </encrypted>
 *          <replace id='prev-read-uuid' xmlns='urn:xmpp:message-correct:0'/>
 *      </message>
 *  Reply to a message
 *      <message id='read-uuid' to='sender@domain.com'>
 *           <encrypted>
 *               <body></body>
 *               <reply to='@sender@domain.com' id='read-uuid' xmlns='urn:xmpp:reply:0'/>
 *           </encrypted>
 *      </message>
 *   <p>
 *   <b>Support for ADVANCE MESSAGE PROCESSING XEP 0079</b>
 *   <i>A protocol that enables an end-to-end entity to specify additional
 *   semantics for XMPP <message/> stanza. This protocol is typically used by client to inform
 *   the receiving server or client how to deliver or render s particular stanza,
 *   such as providing an expiration time or resource-matching strategy
 *   </i>
 * @{@link <a href="https://xmpp.org/extensions/xep-0079.html">Advanced Message Processing</a>}
 * <p>
 * <b>XEP-0482  Call Invites</b>
 * <p>
 * <message from='sender@domain.com' to='receiver@domain.com' id='read-uuid' type='chat'>
 *     <invite video='true' xmlns='urn:xmpp:call-invites:0'>
 *         <jingle sid='sids'/>
 *     </invite>
 * </message>
 *  <p>
 * <message to='mara@example.com' type='chat'>
 *   <retract id='id1' xmlns='urn:xmpp:call-invites:0' />
 * </message>
 * <p>
 * <message to='mara@example.com' type='chat'>
 *   <accept id='id1' xmlns='urn:xmpp:call-invites:0'>
 *     <jingle sid='sid1' jid='mixer@example.com/uuid' />
 *   </accept>
 * </message>
 *
 * <p>
 * <message to='mara@example.com' type='chat'>
 *   <reject id='id1' xmlns='urn:xmpp:call-invites:0' />
 * </message>
 * <p>
 * <message to='mara@example.com' type='chat'>
 *   <left id='id1' xmlns='urn:xmpp:call-invites:0' />
 * </message>
 */
public final class EncryptedMessageHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(EncryptedMessageHandler.class.getName());

    private static final String E2EE_NS     = "urn:xmpp:e2ee:0";
    private static final String REACTIONS_NS = "urn:xmpp:reactions:0";
    private static final String RECEIPTS_NS = "urn:xmpp:receipts";
    private static final String SERVER_RECEIPT_NS = "urn:xmpp:server:receipts";
    private static final String CHAT_NS     = "http://jabber.org/protocol/chatstates";
    private static final String MESSAGE_CORRECTION = "urn:xmpp:message-correct:0";
    private static final String REPLY_NS = "urn:xmpp:reply:0";
    private static final String CALL_NS = "urn:xmpp:call-invites:0";

    private final SessionRegistry registry;
    private final DatabaseManager db;
    private final ServerMetrics metrics;
    private final ReactionHandler reactionHandler;
    private final CRDTNoteHandler crdtNoteHandler;
    private final MessageRepository messageRepository;
    private final ConcurrentHashMap<String, CallState> activeCalls =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "call-scheduler");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> ringTimeout =
            new ConcurrentHashMap<>();

    public EncryptedMessageHandler(SessionRegistry registry,
                          DatabaseManager db,
                          ServerMetrics metrics, ReactionHandler reactionHandler, CRDTNoteHandler crdtNoteHandler, MessageRepository repository) {
        this.registry = registry;
        this.db       = db;
        this.metrics  = metrics;
        this.reactionHandler = reactionHandler;
        this.crdtNoteHandler = crdtNoteHandler;
        this.messageRepository = repository;
    }

    @Override
    public void handle(StartElement element,
                       XMLEventReader reader,
                       Session session) {
        if (!session.isAuthenticated()) {
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
        String toContactId = bareJid(to);
        // Parse the message content
        ParsedMessage parsed = parseMessageContent(reader, id);

        // Update the last seen status of the sender
        session.touchActivity();

        if (parsed.isReceiptOnly()) {
            routeReceipt(parsed, session, toContactId);
            return;
        }

        if (parsed.isChatStateOnly()) {
            routeChatState(parsed, session, toContactId, type);
            return;
        }

        if (parsed.isDeleted()) {
            String stanza = String.format(
                    "<message from='%s' to='%s' type='chat'>" +
                            "<delete xmlns='urn:xmpp:delete:0' id='%s'/>" +
                            "</message>",
                    escapeXml(session.getContactId()),
                    escapeXml(toContactId),
                    parsed.deletedMessageId()
            );
            registry.getByUserId(toContactId).ifPresent(s -> {
                if(s.isAuthenticated()) s.writeXML(stanza);
            });
            logger.info("Delete message sent");
            return;
        }

        if(parsed.isCallType()) {
            String stanza = "";
            String callId = UUID.randomUUID().toString();

            switch (parsed.callAction()) {
                case "invite" ->    {
                    logger.info("Call initiate sessionId is: "+parsed.callId());
                    stanza = String.format("""
                            <message type='chat' from='%s' id='%s'>
                                <invite xmlns='urn:xmpp:call-invites:0'>
                                    <room-name>%s</room-name>
                                    <sid>%s</sid>
                                </invite>
                            </message>
                            """,  session.getUid(), callId,
                            parsed.roomName(), parsed.callId());
                }
                case "ringing" ->   {
                    logger.info("Call ringing sessionId is: "+parsed.callId());
                    stanza = String.format("""
                            <message type='chat' from='%s' id='%s'>
                                <ringing xmlns='urn:xmpp:call-invites:0'>
                                    <sid>%s</sid>
                                </ringing>
                            </message>
                            """,  session.getUid(), callId,
                            parsed.callId());
                }
                case "accept" ->    {
                    logger.info("Call accept sessionId is: "+parsed.callId());
                    stanza = String.format("""
                            <message type='chat' from='%s' id='%s'>
                                <accept xmlns='urn:xmpp:call-invites:0'>
                                    <sid>%s</sid>
                                </accept>
                            </message>
                            """,  session.getUid(), callId,
                            parsed.callId());
                }
                case "reject"   ->  {
                    stanza = String.format("""
                            <message type='chat' from='%s' id='%s'>
                                <reject xmlns='urn:xmpp:call-invites:0'>
                                    <sid>%s</sid>
                                </reject>
                            </message>
                            """,  session.getUid(), callId,
                            parsed.callId());
                }
                case "left"     ->  {
                    stanza = String.format("""
                            <message type='chat' from='%s' id='%s'>
                                <left xmlns='urn:xmpp:call-invites:0'>
                                    <sid>%s</sid>
                                </left>
                            </message>
                            """,  session.getUid(), callId,
                            parsed.callId());
                }

                case "ended" -> {
                    logger.info("Routing call ended stanza ....");
                    stanza = String.format("""
                            <message type='chat' from='%s' to='%s' id='%s'>
                                <call xmlns='urn:xmpp:call:1' sid='%s' action='ended' media='video' duration='%d'/>
                            </message>
                            """, session.getUid(), toContactId, callId,  parsed.callId(), parsed.callDuration());
                }
            }

            String xml = stanza;


            registry.getByUserId(toContactId).ifPresent(s -> {
                if(s.isAuthenticated()) s.writeXML(xml);
            });

            logger.info("Call XML sent is: "+stanza);

            if("invite".equals(parsed.callAction())) {
                PushNotificationService.getInstance()
                        .sendPushCallNotification(toContactId, parsed.roomName(), "video", parsed.callId());
            }
            return;
        }

        if (parsed.encryptedContent() == null && parsed.mediaUrl == null) {
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
        /*TODO: To  be uncomment later String identityKey = parsed.identityKey();
        Optional<String> key = db.getIdentityKey(toContactId);
        if(key.isPresent() &&!key.get().equals(identityKey)) {
            sender.writeXML(String.format("<message type='INVALID_KEY' id='%s' from='%s'> </message>", messageId, toContactId));
            return;
        }*/
        // Build the full stanza XML to forward/store
        String stanzaXml = buildEncryptedStanza(
                parsed.isEdited() ? parsed.replaceId() : messageId, sender.getUid(), toContactId,
                type, parsed
        );

        boolean delivered = false;

        // Try online delivery first
        Optional<Session> recipientSession = registry.getByUserId(toContactId);

        if (recipientSession.isPresent() && recipientSession.get().isAuthenticated()) {
            delivered = recipientSession.get().writeXML(stanzaXml);
        }

        if (delivered) {
            metrics.messageSent();
        } else {
            // Recipient offline - store encrypted ciphertext
            if (db.contactExists(toContactId)) {
                boolean store = messageRepository.insert(
                        parsed.isEdited() ? parsed.replaceId() : messageId,
                        sender.getUid(), toContactId, parsed.encryptedContent(),
                        parsed.msgType(), parsed.replyToId());

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
        sendServerReceipt(sender, parsed.isEdited() ? parsed.replaceId() : messageId);

        // TODO: Send notification to the receiver of the message
        /*PushNotificationService.getInstance()
                .sendMessageNotification(toContactId, "Abdulfattah", type);*/
    }



    /**
     * Routes a delivery/read receipt back to the original sender.
     * Receipts should be stored in the database also
     */
    private void routeReceipt(ParsedMessage parsed,
                              Session sender,
                              String toContactId) {
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
        registry.getByUserId(toContactId).ifPresentOrElse(s ->{
            if(s.isAuthenticated()) {
                boolean sent = s.writeXML(receiptXml);
                if(!sent) {
                    messageRepository.insertReceipt(sender.getUid(), toContactId, parsed.receiptId(), parsed.receiptType());
                }
            }
        }, () -> messageRepository.insertReceipt(sender.getUid(), toContactId, parsed.receiptId(), parsed.receiptType()));
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

        registry.getByUserId(toContactId).ifPresent(s -> {
            if(s.isAuthenticated()) s.writeXML(stanza);
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
                "<encrypted xmlns='%s' msg_type='%s' ",
                E2EE_NS,
                escapeXml(parsed.msgType())
        ));

        if (parsed.mimeType() != null) {
            sb.append(String.format(
                    " mime='%s'", escapeXml(parsed.mimeType())));
        }

        sb.append(">");
        sb.append(parsed.encryptedContent());
        sb.append("</encrypted>");

        // Edited reference
        if(parsed.isEdited()) {
            sb.append(String.format("<replace xmlns='%s' id='%s'/>", MESSAGE_CORRECTION, parsed.replaceId()));
        }

        // Deleted reference
        if(parsed.isDeleted()) {
            sb.append(String.format("<delete xmlns='urn:xmpp:delete:0' id='%s'/>", parsed.deletedMessageId()));
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
        logger.info("End of message stanza: "+sb);
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
        String replaceId         = null;
        String deletedId         = null;
        boolean isCall           = false;
        String callAction        = null;
        String roomName          = null;
        String callId            = null;
        long callDuration        = 0L;

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

                    // Edited stanza
                    else if("replace".equals(name) && MESSAGE_CORRECTION.equals(ns)){
                        logger.info("Processing replace message");
                        replaceId = getAttr(child, "id");
                    }

                    // Read receipt
                    else if ("displayed".equals(name) && RECEIPTS_NS.equals(ns)) {
                        receiptType = "displayed";
                        receiptId   = getAttr(child, "id");
                    }

                    // Deleted message
                    else if("delete".equals(name) && "urn:xmpp:delete:0".equals(ns)) {
                        deletedId = getAttr(child, "id");
                    }

                    // Chat state notifications
                    else if (CHAT_NS.equals(ns)) {
                        chatState = name; // composing|paused|active|inactive|gone
                    }

                    else if("invite".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Call namespace encountered: invite");
                        // The jingle child is empty here, the server populates it later on
                        callAction = "invite";
                        isCall = true;
                    }

                    else if("room-name".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Reading room-name");
                        roomName = readText(reader);
                        depth--;
                    }
                    else if("call".equals(name)){
                        isCall = true;
                        callAction = getAttr(child, "action");
                        callId =getAttr(child, "sid");
                        callDuration = Long.parseLong(getAttr(child,"duration"));
                    }

                    else if("sid".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Call namespace encountered: sid");
                        // The jingle child is empty here, the server populates it later on
                        callId = readText(reader);
                        depth--;
                    }

                    else if("accept".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Accept namespace encountered");
                        // The jingle child is empty here, the server populates it later on
                        callAction = "accept";
                        isCall = true;
                    }

                    else if("reject".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Reject namespace encountered");
                        // The jingle child is empty here, the server populates it later on
                        callAction = "reject";
                        isCall = true;
                    }

                    else if("ringing".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Ringing call namespace encountered");
                        // The jingle child is empty here, the server populates it later on
                        callAction = "ringing";
                        isCall = true;
                    }
                    else if("left".equals(name) && CALL_NS.equals(ns)) {
                        logger.info("Left call namespace encountered");
                        // The jingle child is empty here, the server populates it later on
                        callAction = "left";
                        isCall = true;
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
                encryptedContent,  msgType,
                identityKey, mediaUrl,
                mimeType, replyToId,
                receiptType, receiptId, chatState,
                replaceId, deletedId, isCall, callAction,  roomName,
                callId, callDuration
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

    private boolean isInActiveCall(String userId) {
        return activeCalls.values().stream().anyMatch(state ->
                state.callerId().equals(userId)
                        || state.calleeId().equals(userId));
    }

    // =========================================================================
    // Inner type
    // =========================================================================

    private record ParsedMessage(
            String encryptedContent,
            String msgType,
            String identityKey,
            String mediaUrl,
            String mimeType,
            String replyToId,
            String receiptType,
            String receiptId,
            String chatState,
            String replaceId,
            String deletedMessageId,
            boolean isCallType,
            String callAction,
            String roomName,
            String callId,
            long callDuration
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

        boolean isEdited() {
            return replaceId != null && chatState == null;
        }

        boolean isDeleted()     { return deletedMessageId != null && chatState == null; }
    }


    /**
     * Represents a peeked-at child element of a Message stanza.
     * Carries enough information to route the IQ without fully consuming it.
     */
    private record MessageChild(String localName, String namespace) {

        /**
         * Returns a reader that replays this child element.
         * Used when we need to pass the full IQ body to a sub-handler.
         * In practice we pass the live reader since we only peeked.
         */
        XMLEventReader replayReader(XMLEventReader original) {
            return original;
        }
    }

    private MessageChild peekChild(XMLEventReader reader) {
        try{
            while(reader.hasNext()) {
                XMLEvent event = reader.peek();

                if(event.isStartElement()) {
                    StartElement child = event.asStartElement();
                    String ns = child.getName().getNamespaceURI();
                    String name = child.getName().getLocalPart();

                    return new MessageChild(name, ns);
                }

                if(event.isEndElement()) return null;
                reader.nextEvent();
            }
        }catch(XMLStreamException exception) {
            logger.warning("Error peeking Message child: " + exception.getMessage());
        }

        return null;
    }


    private record CallState(
            String callId,
            String callerId,
            String callerJid,
            String calleeId,
            String calleeJid,
            String callType,
            long startedAt,
            String state
    ) {
        CallState(String callId, String callerId, String callerJid,
                  String calleeId, String calleeJid,
                  String callType, long startedAt) {
            this(callId, callerId, callerJid, calleeId, calleeJid,
                    callType, startedAt, "ringing");
        }

        CallState withState(String newState) {
            return new CallState(callId, callerId, callerJid,
                    calleeId, calleeJid, callType, startedAt, newState);
        }
    }

}