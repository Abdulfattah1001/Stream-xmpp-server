package streammessenger.muc1;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * StAX codec for the muc#user extension. Pure StAX, no third-party XML libs.
 *
 * <h2>Why a hand-rolled codec?</h2>
 * Your stack uses a custom protocol writer. A reflection/JAXB-based mapper would
 * (a) add a dependency and (b) be slower for the hot presence-broadcast path.
 * Hand-written StAX gives us full control over namespace prefixes and ordering,
 * which matters for strict interop.
 */
public final class MucUserCodec {

    private MucUserCodec() {}

    /**
     * Write {@code <x xmlns='muc#user'>...</x>} into an already-open stanza.
     * The caller is responsible for the surrounding {@code <presence>}/{@code <message>}.
     *
     * @param w   stream writer positioned inside the parent element
     * @param ext the extension to serialize (must not be null)
     */
    public static void write(XMLStreamWriter w, MucUserExtension ext) throws XMLStreamException {
        w.writeStartElement(Muc.EL_X);
        w.writeDefaultNamespace(Muc.NS_MUC_USER);

        MucUserExtension.Item item = ext.item();
        if (item != null) {
            w.writeStartElement("item");
            w.writeAttribute("affiliation", item.affiliation.wire());
            w.writeAttribute("role", item.role.wire());
            if (item.jid  != null) w.writeAttribute("jid",  item.jid);
            if (item.nick != null) w.writeAttribute("nick", item.nick);

            if (item.actorJid != null) {
                w.writeStartElement("actor");
                w.writeAttribute("jid", item.actorJid);
                w.writeEndElement();
            }
            if (item.reason != null) {
                w.writeStartElement("reason");
                w.writeCharacters(item.reason);
                w.writeEndElement();
            }
            w.writeEndElement(); // </item>
        }

        for (MucStatus st : ext.statuses()) {
            w.writeStartElement("status");
            w.writeAttribute("code", Integer.toString(st.code()));
            w.writeEndElement();
        }

        w.writeEndElement(); // </x>
    }

    /**
     * Parse an {@code <x xmlns='muc#user'>} element.
     *
     * <p><b>Cursor contract:</b> the reader must be positioned on the
     * {@code START_ELEMENT} event for {@code <x>}. On return the cursor sits on
     * the matching {@code END_ELEMENT}. This is the standard StAX "consume one
     * subtree" idiom and keeps this method composable with a larger stanza
     * parser.</p>
     */
    public static MucUserExtension read(XMLStreamReader r) throws XMLStreamException {
        MucUserExtension ext = new MucUserExtension();
        MucUserExtension.Item item = null;

        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "item":
                        item = new MucUserExtension.Item();
                        item.affiliation = Affiliation.fromWire(attr(r, "affiliation"));
                        item.role        = Role.fromWire(attr(r, "role"));
                        item.jid         = attr(r, "jid");
                        item.nick        = attr(r, "nick");
                        break;
                    case "actor":
                        if (item != null) item.actorJid = attr(r, "jid");
                        break;
                    case "reason":
                        if (item != null) item.reason = r.getElementText(); // advances cursor
                        break;
                    case "status":
                        int code = Integer.parseInt(attr(r, "code"));
                        for (MucStatus s : MucStatus.values())
                            if (s.code() == code) ext.addStatus(s);
                        break;
                    default: /* ignore unknown children for forward-compat */ break;
                }
            } else if (event == XMLStreamReader.END_ELEMENT
                       && Muc.EL_X.equals(r.getLocalName())) {
                break; // end of our subtree
            }
        }
        if (item != null) ext.withItem(item);
        return ext;
    }

    private static String attr(XMLStreamReader r, String name) {
        return r.getAttributeValue(null, name);
    }
}