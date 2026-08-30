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

import streammessenger.api.SimpleJson;
import streammessenger.db.DatabaseManager;
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
    private static final String CRDT_NS = "urn:xmpp:crdt-note:0";
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
        deliverOfflineMessages(session, session.getUid());

        // 3. Deliver offline receipts
        deliverOfflineReceipts(session, contactId);

        DatabaseManager.SyncBacklogResponse responses = db.getOptimizedSyncBacklog(session.getUid());

        if(responses.groupSyncs() != null && !responses.groupSyncs().isEmpty()){
            for(DatabaseManager.GroupSyncPayload payload : responses.groupSyncs()){
                for(DatabaseManager.GroupEventDelta delta : payload.deltas()){
                    String type = delta.eventType();
                    SimpleJson json = SimpleJson.parse(delta.payload());
                    long createdAt = delta.createdAt();
                    switch (type){
                        case "metadata_changed", "description_changed":
                            session.writeXML(String.format("""
                            <message id='%s' to='%s' from='%s' type='%s'>
                                <system xmlns='urn:xmpp:group:0' timestamp='%b'>
                                    <event type='description_changed' actor='%s'>
                                        <description>%s</description>
                                    </event>
                                </system>
                            </message>
                            """, delta.eventId(), session.getResource(), payload.groupId()+"/"+delta.actorUid(), "groupchat", createdAt,
                                    delta.actorUid(), json.getString("description")));
                            break;
                        case "name_changed":
                            session.writeXML(String.format("""
                            <message id='%s' to='%s' from='%s' type='%s'>
                                <system xmlns='urn:xmpp:group:0' timestamp='%b'>
                                    <event type='name_changed' actor='%s'>
                                        <name>%s</name>
                                    </event>
                                </system>
                            </message>
                            """, delta.eventId(), session.getResource(), payload.groupId()+"/"+delta.actorUid(), "groupchat", createdAt,
                                    delta.actorUid(), json.getString("name")));
                            break;
                        case "avatar_changed":
                            session.writeXML(String.format("""
                            <message id='%s' to='%s' from='%s' type='%s'>
                                <system xmlns='urn:xmpp:group:0' timestamp='%b'>
                                    <event type='avatar_changed' actor='%s'>
                                        <avatar_url>%s</avatar_url>
                                    </event>
                                </system>
                            </message>
                            """, delta.eventId(), session.getResource(), payload.groupId()+"/"+delta.actorUid(), "groupchat", createdAt,
                                    delta.actorUid(), json.getString("avatar_url")));
                            break;
                        case "member_joined_via_link":
                            session.writeXML(String.format("""
                            <message id='%s' to='%s' from='%s' type='%s'>
                                <system xmlns='urn:xmpp:group:0' timestamp='%b'>
                                    <event type='member_joined_via_link' actor='%s' subject='%s'>
                                        <member user_id='' jid='' avatar_url='' display_name='' display_status='' phone_number=''>%s</member>
                                    </event>
                                </system>
                            </message>
                            """, delta.eventId(), session.getResource(), payload.groupId()+"/"+delta.actorUid(), "groupchat", createdAt,
                                    delta.actorUid(), delta.targetUid(),json.getString("avatar_url")));
                            break;
                        case "only_admins_edit":
                            String xml = String.format("""
                                    <message id='%s' to='%s' from='%s' type='%s'>
                                        <system xmlns='urn:xmpp:group:0' timestamp='%b'>
                                            <event type='only_admins_can_edit_info' actor='%s' subject='%s'>
                                               <state>%b</state>
                                            </event>
                                        </system>
                                    </message>
                                    """,
                                    delta.eventId(), session.getJid(), payload.groupId() +"/"+delta.actorUid(), "groupchat", createdAt,
                                    delta.actorUid(), delta.targetUid(), json.getBoolean("only_admins_edit"));
                            session.writeXML(xml);
                            break;
                        case "only_admins_send":
                            String xml1 = String.format("""
                                    <message id='%s' to='%s' from='%s' type='%s'>
                                        <system xmlns='urn:xmpp:group:0' timestamp='%b'>
                                            <event type='only_admins_can_send_message' actor='%s' subject='%s'>
                                               <state>%b</state>
                                            </event>
                                        </system>
                                    </message>
                                    """,
                                    delta.eventId(), session.getJid(), payload.groupId() +"/"+delta.actorUid(), "groupchat",createdAt,
                                    delta.actorUid(), delta.targetUid(), json.getBoolean("only_admins_send"));
                            logger.info(xml1);
                            session.writeXML(xml1);
                            break;
                    }
                    // Updates the last sync version for the user to prevent resending of already sent events
                    db.updateGroupMemberLastSyncVersion(session.getUid(), payload.groupId(), payload.latestServerVersion());
                }
            }
        }

        // 5. Deliver pending event items
        List<DatabaseManager.OfflineMailEvent> events = db.getOfflineMailEvents(session.getUid());
        if(events.isEmpty()){
            logger.info("Mail event is empty");
        }else{
            for(DatabaseManager.OfflineMailEvent event: events){
                String id = UUID.randomUUID().toString();
                SimpleJson json = SimpleJson.parse(event.payload());
                StringBuilder sb = new StringBuilder(String.format("<message from='%s' to='%s' id='%s'>", event.senderId(), session.getJid(), id));
                sb.append("<event xmlns='http://jabber.org/protocol/pubsub#event'>");
                sb.append("<items node='urn:xmpp:profile-metadata'>");
                sb.append("<item id='current'>");
                sb.append("<profile xmlns='metadata:ns'>");
                if(json.hasKey("avatar_url")) sb.append(String.format("<avatar_url>%s</avatar_url>", json.getString("avatar_url")));
                if(json.hasKey("bio")) sb.append(String.format("<bio>%s</bio>", json.getString("bio")));
                if(json.hasKey("display_name")) sb.append(String.format("<display_name>%s</display_name>", json.getString("display_name")));
                sb.append("</profile>");
                sb.append("</item>");
                sb.append("</items>");
                sb.append("</event>");
                sb.append("</message>");

                String xml = sb.toString();
                logger.info("Final xml stanza to sent is: "+xml);
                session.writeXML(xml);
            }
        }

        /*List<DatabaseManager.Note> notes = db.fetchUserNotes(session.getUid());

        if(notes.isEmpty()){
            logger.info("Notes is empty for this user");
        }else{
            for(DatabaseManager.Note nt : notes){
                session.writeXML(String.format("""
                        <message type='crdt'>
                        <crdt xmlns='%s' action='invited'>
                        <note_id>%s</note_id>
                        <title>%s</title>
                        <inviter>%s</inviter>
                        <conversation_id>%s</conversation_id>
                        </crdt>
                        </message>
                        """, CRDT_NS, nt.noteId(), nt.title(), nt.creatorId(), nt.conversationId()));
            }
        }*/

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
            String type = msg.type();

            if("chat".equals(type)){
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
                }
            }else {
                String stanza = String.format(
                        "<message id='%s' from='%s' to='%s' type='groupchat'>" +
                                "<body xmlns='%s' msg_type='%s' iv=''>%s</body>" +
                                "<request xmlns='%s'/>" +
                                "<delay xmlns='urn:xmpp:delay' from='%s' stamp='%s'/>" +
                                "</message>",
                        escapeXml(msg.messageId()),
                        escapeXml(msg.groupId()+"/"+msg.fromJid()),
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
                }
            }
        }
    }

    private void deliverOfflineReceipts(Session session, String contactId){
        String id = contactId;
        if(contactId.contains("@")) id = contactId.split("@")[0];
        List<DatabaseManager.OfflineReceipt> receipts = db.getOfflineReceipt(id);

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
                    .append(idleSince)
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