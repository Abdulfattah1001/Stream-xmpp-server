package streammessenger.stanza;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.profile.Profile;
import streammessenger.profile.ProfileMutation;
import streammessenger.profile.UpdateService;
import streammessenger.roster.ProfileVersion;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.sync.SyncNode;
import streammessenger.sync.Version;

public class PubSubHandler implements StanzaHandler {
    public static final String PROFILE_SYNC_NS = "urn:xmpp:profile-sync:1";
    private static final Logger logger = Logger.getLogger(PubSubHandler.class.getName());
    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final ConnectionPool pool;
    private final SyncNode syncNode;

    public PubSubHandler(ConnectionPool pool, DatabaseManager db, SessionRegistry registry, SyncNode syncNode){
        this.db = db; this.pool = pool;
        this.registry = registry; this.syncNode = syncNode;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {

        String iqId = getAttr(element, "id");

        if (!session.isAuthenticated()) {
            sendError(session, null, "not-authorized");
            consumeElement(reader);
            return;
        }

        ParsedProfileMetadata p = parsedProfileMetadata(reader);

        if(p == null){
            sendError(session, iqId, "bad-request");
            return;
        }

        if(p.displayStatus() != null) {
            ProfileMutation mutation = ProfileMutation.statusText(p.displayStatus());
            try {
                UpdateService updateService = syncNode.getProfileUpdateService();
                UpdateService.UpdateResult result = updateService.update(session.getUid(), null, mutation);
                logger.info("Updated result is: "+result.profile().statusText());
            } catch (SQLException e) {
                logger.info("Error occurred updating user display status: "+e.getMessage());
            }
        }

        if(p.displayName()  != null) {
            ProfileMutation mutation = ProfileMutation.statusText(p.displayName());
            try {
                UpdateService updateService = syncNode.getProfileUpdateService();
                UpdateService.UpdateResult result = updateService.update(session.getUid(), null, mutation);
            } catch (SQLException e) {
                logger.info("Error occurred updating user display status: "+e.getMessage());
            }
        }

        if(p.avatarUrl() != null) {
            ProfileMutation mutation = ProfileMutation.statusText(p.avatarUrl());
            try {
                UpdateService updateService = syncNode.getProfileUpdateService();
                UpdateService.UpdateResult result = updateService.update(session.getUid(), null, mutation);
                logger.info("Successfully updated the avatar url: "+result.profile().avatarUrl());
            } catch (SQLException e) {
                logger.info("Error occurred updating user display status: "+e.getMessage());
            }
        }
    }


    public void handleGet(StartElement element, XMLEventReader reader, Session session) throws XMLStreamException {
        String id = getAttr(element, "id");

        while(reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            try{
                if(event.isStartElement()){
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();

                    if("profile-get".equals(name)) {
                        String uid = getAttr(se, "user");
                        int have = Integer.parseInt(Objects.requireNonNull(getAttr(se, "have")));
                        logger.info("The version have is: "+have);
                        Profile profile = syncNode.getProfileUpdateService().fetchProfile(uid);
                        // If the version fetched is less than or equal to the client
                        // version, then the server is behind, so no get is done
                        // TODO: To be extended for list of users later
                        if(profile.version() <= have) {
                            logger.info("Version has changed");
                        } else {
                            // TODO: To be refined later into StringBuilder to prevent sending a null values to the client
                            String xml = String.format("""
                                <iq type='result' id='%s'>
                                    <profiles xmlns='%s'>
                                        <profile user='%s' version='%d' updated='%d'>
                                            <display-status>%s</display-status>
                                            <display-name>%s</display-name>
                                            <username>%s</username>
                                            <avatar-url>%s</avatar-url>
                                        </profile>
                                    </profiles>
                                """, UUID.randomUUID(), "urn:xmpp:profile-sync:1",
                                    profile.userId(), profile.version(), System.currentTimeMillis(),
                                    profile.statusText(), profile.displayName(), profile.username(),
                                    profile.avatarUrl());
                            session.writeXML(xml);
                        }
                    }

                    /*if("profile-sync".equals(name)) {
                        long seq = Long.parseLong(Objects.requireNonNull(getAttr(se, "since")));
                        logger.info("sync since seq: "+seq);
                        Set<String> contacts = db.getContactEdges(session.getUid());

                        try{
                            Connection connection = pool.getConnection();
                            List<Version>  changed = profileUpdateServices.latestSince(connection, seq, contacts);

                            StringBuilder builder = new StringBuilder(128 + changed.size() * 80);
                            builder.append(String.format("<message id='%s'>", UUID.randomUUID()))
                                    .append("<profile-invalidate xmlns='urn:xmpp:profile-sync:1'>");
                            for(Version v: changed) {
                                builder.append("<item user=\"").append(escapeXml(v.userId())).append("\" version=\"").append(v.version()).append("\" seq=\"").append(1).append("\"/>");
                            }
                            builder.append("</profile-invalidate>");
                            builder.append("</message>");

                            String xml = builder.toString();
                            session.writeXML(xml);
                            connection.commit();
                        } catch (SQLException e) {
                            logger.info("Error occurred getting latest version: "+e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }*/
                }
            }catch(SQLException exception){
                logger.info("Exceptioin occured: handleGet "+exception.getMessage());
            }

            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("iq")) {
                break;
            }
        }
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