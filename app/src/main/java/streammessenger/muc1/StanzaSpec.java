package streammessenger.muc1;


import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * A serialization recipe for one outbound stanza.
 *
 * <p>The {@link #render()} method writes a complete stanza using StAX. In your
 * real infrastructure you'd swap the StringWriter for whatever sink your custom
 * protocol stack expects (e.g. write directly into the session's output stream).
 * The construction logic stays identical — only the writer target changes.</p>
 */
public abstract class StanzaSpec {

    private static final XMLOutputFactory XOF = XMLOutputFactory.newFactory();

    /** Render to a String of XML (handy for tests / simple transports). */
    public final String render() {
        StringWriter sw = new StringWriter(256);
        try {
            XMLStreamWriter w = XOF.createXMLStreamWriter(sw);
            write(w);
            w.flush();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to render MUC stanza", e);
        }
        return sw.toString();
    }

    /** Subclasses emit a full stanza element. */
    protected abstract void write(XMLStreamWriter w) throws XMLStreamException;

    // ─────────── factory methods used by Room ───────────

    /** Available presence describing an occupant to a recipient. */
    public static StanzaSpec occupantPresence(String roomJid, Occupant about,
                                              String revealRealJid, boolean self,
                                              List<MucStatus> statuses) {
        return new StanzaSpec() {
            @Override protected void write(XMLStreamWriter w) throws XMLStreamException {
                w.writeStartElement("presence");
                w.writeAttribute("from", roomJid + "/" + about.nick());
                // 'to' filled by the dispatcher per-recipient; left implicit here.

                MucUserExtension ext = new MucUserExtension();
                MucUserExtension.Item item = new MucUserExtension.Item();
                item.affiliation = about.affiliation();
                item.role = about.role();
                item.jid = revealRealJid; // null in anonymous rooms
                ext.withItem(item);
                if (self) ext.addStatus(MucStatus.SELF_PRESENCE);
                for (MucStatus s : statuses) ext.addStatus(s);

                MucUserCodec.write(w, ext);
                w.writeEndElement();
            }
        };
    }

    /** Unavailable presence (leave/kick/ban). */
    public static StanzaSpec occupantUnavailable(String roomJid, Occupant about,
                                                 Affiliation finalAff, boolean self,
                                                 MucStatus reasonCode, String actorJid,
                                                 String reason) {
        return new StanzaSpec() {
            @Override protected void write(XMLStreamWriter w) throws XMLStreamException {
                w.writeStartElement("presence");
                w.writeAttribute("type", "unavailable");
                w.writeAttribute("from", roomJid + "/" + about.nick());

                MucUserExtension ext = new MucUserExtension();
                MucUserExtension.Item item = new MucUserExtension.Item();
                item.affiliation = finalAff;
                item.role = Role.NONE;
                item.actorJid = actorJid;
                item.reason = reason;
                ext.withItem(item);
                if (self) ext.addStatus(MucStatus.SELF_PRESENCE);
                if (reasonCode != null) ext.addStatus(reasonCode);

                MucUserCodec.write(w, ext);
                w.writeEndElement();
            }
        };
    }

    /** Groupchat message carrying the current subject. */
    public static StanzaSpec subject(String roomJid, String subject) {
        return new StanzaSpec() {
            @Override
            protected void write(XMLStreamWriter w) throws XMLStreamException {
                w.writeStartElement("message");
                w.writeAttribute("type", "groupchat");
                w.writeAttribute("from", roomJid);
                w.writeStartElement("subject");
                w.writeCharacters(subject == null ? "" : subject);
                w.writeEndElement();
                w.writeEndElement();
            }
        };
    }
}