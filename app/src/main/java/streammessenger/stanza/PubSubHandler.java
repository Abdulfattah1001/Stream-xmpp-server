package streammessenger.stanza;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

public class PubSubHandler implements StanzaHandler{
    private static final Logger logger = Logger.getLogger(PubSubHandler.class.getName());
    private final DatabaseManager db;
    private final SessionRegistry registry;

    public PubSubHandler(DatabaseManager db, SessionRegistry registry){
        this.db = db;
        this.registry = registry;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {
        logger.info("Processing PubSub stanza");

        String iqId = getAttr(element, "id");
        if (!session.isAuthenticated()) {
            sendError(session, null, "not-authorized");
            consumeElement(reader);
            return;
        }

        String contactId = session.getContactId();
        // Parsed the pubsub stanza here
        ParsedProfileMetadata p = parsedProfileMetadata(reader);

        if(p == null){
            sendError(session, iqId, "bad-request");
            return;
        }

        if(p.displayStatus() != null){
            boolean updated = db.changeDisplayStatus(session.getUid(),  p.displayStatus());
            if(updated){
                session.writeXML(String.format("<iq type='result' id='%s'/>", iqId));
                consumeElement(reader);

                // Fanout to online contacts
                List<String> uids = db.getContacts(session.getUid());
                if(uids.isEmpty()) return;

                for(String uid: uids){
                    if(uid.equals(session.getUid())) continue;
                    if(registry.isOnline(uid+"@localhost")){
                        String id = UUID.randomUUID().toString();
                        String xml = String.format("""
                                <message from='%s' to='%s' id='%s'>
                                  <event xmlns='http://jabber.org/protocol/pubsub#event'>
                                    <items node='urn:xmpp:profile-metadata'>
                                      <item id='%s'>
                                        <profile xmlns='metadata:ns'>
                                          <bio>Coding late into the night...</bio>
                                          <theme>dark</theme>
                                        </profile>
                                      </item>
                                    </items>
                                  </event>
                                </message>
                                """, session.getContactId(), uid, id, UUID.randomUUID().toString());
                        Optional<Session> receiverSession = registry.getByContactId(uid+"@localhost");
                        receiverSession.get().writeXML(xml);
                        logger.info("Event sent");
                    }else{
                        // TODO: Persist the updates for the user (mailbox) architecture
                        logger.info("The user is offline, persisting");
                    }
                }
                /*Thread.ofVirtual().start(()->{
                    List<String> uids = db.getContacts(session.getUid());
                    if(uids.isEmpty()) return;

                    for(String uid: uids){
                        if(registry.isOnline(uid)){
                            // TODO: Send the updates to the user
                            logger.info("The user is online");
                        }else{
                            // TODO: Persist the updates for the user (mailbox) architecture
                            logger.info("The user is offline, persisting");
                        }
                    }
                });*/
            }
        }

        if(p.avatarUrl() != null) {
            boolean updated = db.changeAvatarUrl(session.getUid(), p.avatarUrl());
            if(updated){
                // This should be moved to a background thread
                List<String> uids = db.getContacts(session.getUid());

                if(uids.isEmpty()) return;

                for(String uid: uids){
                    if(uid.equals(session.getUid())) continue;

                    if(registry.isOnline(uid+"@localhost")){
                        String id = UUID.randomUUID().toString();
                        String xml = String.format("""
                                <message from='%s' to='%s' id='%s'>
                                  <event xmlns='http://jabber.org/protocol/pubsub#event'>
                                    <items node='urn:xmpp:profile-metadata'>
                                      <item id='current'>
                                        <profile xmlns='metadata:ns'>
                                          <avatar_url>%s</avatar_url>
                                          <theme>dark</theme>
                                        </profile>
                                      </item>
                                    </items>
                                  </event>
                                </message>
                                """, session.getUid(), uid, id, p.avatarUrl());
                        Optional<Session> receiverSession = registry.getByContactId(uid+"@localhost");
                        receiverSession.ifPresent(s -> s.writeXML(xml));
                    }else{
                        logger.info("Caching the event of profile metadata change");
                        db.storeMailEvent(session.getUid(), uid, "avatar_url", p.avatarUrl());
                    }
                }
            }
        }
    }

    private void handleProfileMetadataChange(Session session){

    }

    private ParsedProfileMetadata parsedProfileMetadata(XMLEventReader reader){
        String displayName      = null;
        String displayStatus    = null;
        String avatarUrl        = null;
        String bio              = null;

        try{
            int depth = 1;
            while(reader.hasNext() && depth > 0){
                XMLEvent event = reader.nextEvent();
                if(event.isStartElement()){
                    depth++;
                    StartElement se = event.asStartElement();
                    String name     = se.getName().getLocalPart();
                    String ns =     se.getName().getNamespaceURI();

                    if("avatar_url".equals(name)){ avatarUrl = readText(reader); }

                    if("display_name".equals(name)) { displayName = readText(reader); }

                    if("display_status".equals(name)) { displayStatus = readText(reader); }

                    if("bio".equals(name)) { bio = readText(reader); }
                }

                if(event.isEndElement()) depth--;
                if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("pubsub") ||
                        event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("iq")){
                    break;
                }
            }
        }catch(XMLStreamException e){
            logger.warning("parsedProfileMetadata error: "+e.getMessage());
            return null;
        }

        return new ParsedProfileMetadata(displayName, displayStatus, avatarUrl, bio);
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

    private void sendError(Session s, String iqId, String cond) {
        s.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='cancel'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
                cond
        ));
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    public record ParsedProfileMetadata(
            String displayName,
            String displayStatus,
            String avatarUrl,
            String bio
    ){}
}