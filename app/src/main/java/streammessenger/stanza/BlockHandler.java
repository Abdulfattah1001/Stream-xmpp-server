package streammessenger.stanza;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

public class BlockHandler implements StanzaHandler {
    private static final Logger logger = Logger.getLogger(BlockHandler.class.getName());
    private static final String BLOCK_NS = "urn:xmpp:custom:block:0";

    private final DatabaseManager db;
    private final SessionRegistry registry;

    public BlockHandler(DatabaseManager db, SessionRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    @Override
    public void handle(StartElement iqElement, XMLEventReader reader, Session session) {
        logger.info("Handling block stanza");
        String iqId = getAttr(iqElement, "id");

        if (!session.isAuthenticated()) {
            sendError(session, iqId, "not-authorized", "auth");
            consumeElement(reader);
            return;
        }

        String uid = session.getUid();;

        try {
            XMLEvent next = reader.nextTag();
            if (!next.isStartElement()) {
                sendError(session, iqId, "bad-request", "modify");
                return;
            }
            String action = next.asStartElement().getName().getLocalPart();
            logger.info("Action is: "+action);

            switch (action) {
                case "block"     -> handleBlock(session, iqId, uid, reader);
                case "unblock"   -> handleUnblock(session, iqId, uid, reader);
                case "blocklist" -> handleBlocklistGet(session, iqId, uid, reader);
                default -> {
                    sendError(session, iqId, "bad-request", "modify");
                    consumeElement(reader);
                }
            }
        } catch (Exception e) {
            logger.warning("Block handler error: " + e.getMessage());
            sendError(session, iqId, "internal-server-error", "cancel");
        }
    }

    private void handleBlock(Session session, String iqId, String userJid, XMLEventReader reader)
            throws XMLStreamException {
        List<String> targets = readItems(reader); // consumes through </block>
        for (String target : targets) {
            if (target.equalsIgnoreCase(userJid)) continue;
            db.blockUser(userJid, target);
            notifyPresenceUnavailable(userJid, target); // tell both sides to drop presence
        }
        session.writeXML(iqResult(iqId, userJid));
        pushBlocklistUpdate(userJid, targets, "block", session);
    }

    private void handleUnblock(Session session, String iqId, String userJid, XMLEventReader reader)
            throws XMLStreamException {
        List<String> targets = readItems(reader);
        if (targets.isEmpty()) {
            db.unblockAll(userJid);
        } else {
            targets.forEach(t -> db.unblockUser(userJid, t));
        }
        session.writeXML(iqResult(iqId, userJid));
        pushBlocklistUpdate(userJid, targets, "unblock", session);
    }

    private void handleBlocklistGet(Session session, String iqId, String userJid, XMLEventReader reader)
            throws XMLStreamException {
        consumeElement(reader);
        List<String> blocked = db.getBlockList(userJid);
        StringBuilder items = new StringBuilder();
        blocked.forEach(j -> items.append("<item jid='").append(escapeXml(j)).append("'/>"));
        session.writeXML(String.format(
                "<iq type='result' id='%s' to='%s'><blocklist xmlns='%s'>%s</blocklist></iq>",
                escapeXml(iqId), escapeXml(userJid), BLOCK_NS, items));
    }

    private List<String> readItems(XMLEventReader reader) throws XMLStreamException {
        List<String> jids = new ArrayList<>();
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                depth++;
                StartElement se = event.asStartElement();
                if (se.getName().getLocalPart().equals("item")) {
                    String jid = getAttr(se, "jid");
                    if (jid != null) jids.add(jid);
                }
            } else if (event.isEndElement()) {
                depth--;
            }
        }
        return jids;
    }

    private void notifyPresenceUnavailable(String owner, String target) {
        registry.getSessionsByUserId(target).forEach(s ->
                s.writeXML(String.format("<presence from='%s' type='unavailable'/>", escapeXml(owner))));
    }

    private void pushBlocklistUpdate(String userJid, List<String> targets, String action, Session origin) {
        for (Session s : registry.getSessionsByUserId(userJid)) {
            if (s == origin) continue;
            StringBuilder items = new StringBuilder();
            targets.forEach(j -> items.append("<item jid='").append(escapeXml(j)).append("'/>"));
            s.writeXML(String.format("<iq type='set'><%s xmlns='%s'>%s</%s></iq>",
                    action, BLOCK_NS, items, action));
        }
    }

    private String iqResult(String iqId, String userJid) {
        return String.format("<iq type='result' id='%s' to='%s'/>", escapeXml(iqId), escapeXml(userJid));
    }

    private void sendError(Session session, String iqId, String condition, String type) {
        session.writeXML(String.format(
                "<iq type='error'%s><error type='%s'><%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "", type, condition));
    }

    private String escapeXml(String s) { /* same as PrivacyHandler */ return s == null ? "" : s
            .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("'","&apos;").replace("\"","&quot;"); }

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

    private String getAttr(StartElement element, String name) {
        var attr = element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }
}