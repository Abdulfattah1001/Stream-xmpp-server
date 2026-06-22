package streammessenger.stanza;


import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.namespace.QName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.group.repository.GroupRepository;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles <presence> stanzas.
 * Broadcasts presence updates to all currently connected sessions.
 * <p>
 * Stateless singleton.
 */
@SuppressWarnings("unused")
public final class PresenceHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(PresenceHandler.class.getName());
    private final SessionRegistry registry;

    private static final String RECEIPTS_NS = "urn:xmpp:receipts";
    private static final String E2EE_NS     = "urn:xmpp:e2ee:0";
    private final DatabaseManager db;

    public PresenceHandler(SessionRegistry registry, DatabaseManager db) {
        this.registry = registry;
        this.db = db;
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
        logger.info("Broadcasting the current user session presence: "+session.getJid());
        int broadcast = 0;
        for (Session other : registry.getAllSessions()) {
            if (!other.getSessionId().equals(session.getSessionId()) && other.isAuthenticated()) {
                other.writeXML(presenceXml);
                broadcast++;
            }
        }

        logger.fine("Presence broadcast from " + session.getJid()
                + " to " + broadcast + " sessions");
        deliverPendingItems(session);

        // TODO: Fetch the last ULID received by the client from the server
        /*List<GroupRepository.UnifiedTimelineItem> offlineMessages = db.getUnifiedTimelineDelta(session.getUid(), "00000000000000000000000000", 100);

        if(!offlineMessages.isEmpty()) {
            for(GroupRepository.UnifiedTimelineItem entity : offlineMessages){
                String eventType  = entity.eventType();
                String category = entity.eventCategory();
                switch (category){
                    case "chat" ->  {
                        logger.info("Chat Message ["+entity.encryptedContent()+"]");
                        String messageId = entity.eventRefId();
                        String senderId = entity.senderId();
                        String encryptedContent = entity.encryptedContent();
                        String contentType = entity.eventType();
                    }
                    case "groupchat" -> {
                        String messageId = entity.eventRefId();
                        String groupId = entity.groupId();
                        String sender = entity.senderId();
                        String content = entity.encryptedContent();
                        String xml = String.format("<message id='%s' type='groupchat' from='%s'>" +
                                "<body>%s</body>" +
                                "</message>", messageId, groupId+"@conference.omnyrex.com/"+sender, content);
                        session.writeXML(xml);
                    }
                    case "system" -> {
                        logger.info("System Events ["+entity.encryptedContent()+"]");
                        String messageId = entity.eventRefId();
                    }
                }
            }
        }else {
            logger.info("Events is empty");
        }*/

        //TODO: serverContext.getSenderKeyManager().deliverMissedRotations(session, session.getContactId());
    }


    /**
     * After resource binding, deliver everything that was held for this user:
     *  1. Offline messages (stored while they were logged out)
     *  2. Pending subscription requests (friend requests received while offline)
     * <p>
     * These are delivered in order: subscriptions first (so roster is up to date)
     * then messages (so they appear after the contact list is current).
     */
    private void deliverPendingItems(Session session) {
        String contactId = session.getContactId();
        // 1. Deliver pending subscription requests
        deliverPendingSubscriptions(session, contactId);

        // 2. Deliver offline messages
        deliverOfflineMessages(session, contactId);

        // 3. Deliver offline receipts
        deliverOfflineReceipts(session, contactId);
    }

    /**
     * Delivers offline messages stored while the user was disconnected.
     * <p>
     * Messages are delivered with XEP-0203 Delayed Delivery timestamps
     * so the client knows when they were originally sent.
     * <p>
     * Example:
     *   <message from='bob@domain' to='alice@domain'>
     *     <body>Hello!</body>
     *     <delay xmlns='urn:xmpp:delay'
     *            from='domain.com'
     *            stamp='2024-01-15T10:30:00Z'/>
     *   </message>
     */
    private void deliverOfflineMessages(Session session, String contactId) {
        List<DatabaseManager.OfflineMessage> messages = db.fetchOfflineMessages(contactId);

        if (messages.isEmpty()) return;

        logger.info("Delivering " + messages.size() + " offline messages to " + contactId);

        for (DatabaseManager.OfflineMessage msg : messages) {
            // Format timestamp as XEP-0082 datetime string
            String timestamp = formatTimestamp(msg.createdAt());


            String stanza = String.format(
                    "<message id='%s' from='%s' to='%s' type='chat'>" +
                            "<encrypted xmlns='%s' msg_type='%s' iv=''>%s</encrypted>" +
                            "<request xmlns='%s'/>" +
                            "<delay xmlns='urn:xmpp:delay' from='%s' stamp='%s'/>" +
                       "</message>",
                    escapeXml(msg.messageId()),
                    escapeXml(msg.fromJid()),
                    escapeXml(contactId),
                    E2EE_NS,
                    escapeXml(msg.messageType()),
                    escapeXml(msg.body()),
                    RECEIPTS_NS,
                    escapeXml(extractDomain(contactId)),
                    timestamp
            );
            boolean sent = session.writeXML(stanza);

            if(!sent){
                db.storeEncryptedMessage(
                        msg.fromJid(),
                        contactId,
                        msg.messageId(),
                        "text",
                        msg.body(),
                        UUID.randomUUID().toString(),
                        "",
                        "",
                        "",
                        0,
                        ""
                );
            }else{
                logger.info("Offline message sent");
            }
        }
    }

    private void deliverOfflineReceipts(Session session, String contactId){
        List<DatabaseManager.OfflineReceipt> receipts = db.getOfflineReceipt(contactId);

        if (receipts.isEmpty()) return;

        logger.info("Delivering " + receipts.size() + " offline receipts to " + contactId);

        for(DatabaseManager.OfflineReceipt rec : receipts){
            session.writeXML(String.format(
                    "<message id='%s' from='%s' to='%s'>" +
                            "<%s xmlns='%s' id='%s'/>" +
                            "</message>",
                    UUID.randomUUID(),
                    escapeXml(session.getContactId()),
                    escapeXml(rec.toUid()),
                    rec.receiptType(), // "received" or "displayed"
                    RECEIPTS_NS,
                    escapeXml(rec.messageId())
            ));
        }
    }


    /**
     * Delivers pending subscription requests (friend requests received while offline).
     * <p>
     * Example: Bob sent Alice a friend request while Alice was offline.
     * When Alice logs in and binds, she receives Bob's subscribe request.
     */
    private void deliverPendingSubscriptions(Session session, String contactId) {
        List<DatabaseManager.PendingSubscription> pending =
                db.fetchPendingSubscriptions(contactId);

        if (pending.isEmpty()) return;

        logger.info("Delivering " + pending.size()
                + " pending subscriptions to " + contactId);

        for (DatabaseManager.PendingSubscription sub : pending) {
            String stanza = String.format(
                    "<presence type='%s' from='%s' to='%s'/>",
                    escapeXml(sub.type()),
                    escapeXml(sub.fromJid()),
                    escapeXml(contactId)
            );
            session.writeXML(stanza);
        }
    }


    private String extractDomain(String jid) {
        if (jid == null) return "";
        int at = jid.indexOf('@');
        if (at == -1) return jid;
        int slash = jid.indexOf('/', at);
        return slash == -1 ? jid.substring(at + 1) : jid.substring(at + 1, slash);
    }


    /**
     * Formats a SQL Timestamp as an XEP-0082 / ISO 8601 datetime string.
     * Example: "2024-01-15T10:30:00.000Z"
     */
    private String formatTimestamp(java.sql.Timestamp ts) {
        if (ts == null) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") {{
            setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        }}.format(ts);
    }
    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;")
                .replace("\"", "&quot;");
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

    private String buildPresenceStanza(String from, String type, String show, Instant idleSince) {
        StringBuilder sb = new StringBuilder("<presence from='")
                .append(from != null ? from : "unknown").append("'");
        if (type != null) sb.append(" type='").append(type).append("'");
        sb.append(">");
        if (show != null) sb.append("<show>").append(show).append("</show>");
        if (idleSince != null) {
            sb.append("<idle xmlns='urn:xmpp:idle:1' since='")
                    .append(idleSince.toString())
                    .append("'/>");
        }
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