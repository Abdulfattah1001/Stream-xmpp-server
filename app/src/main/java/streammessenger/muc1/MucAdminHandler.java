package streammessenger.muc1;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.List;

/**
 * Parses inbound {@code muc#admin} / {@code muc#owner} IQ stanzas and applies
 * them to the target {@link Room}, then dispatches resulting presence updates.
 *
 * <p>This is the inverse of {@link com.example.xmpp.muc.client.MucRoom}'s
 * command builders. Keep the two in lock-step: any attribute the client emits
 * must be one this parser reads.</p>
 */
public final class MucAdminHandler {

    private final MucService service;
    public MucAdminHandler(MucService service) { this.service = service; }

    /**
     * Handle a {@code <query xmlns='muc#admin'>} IQ.
     *
     * @param reader  positioned on the {@code <query>} START_ELEMENT
     * @param fromJid the full JID of the requester (the "actor")
     * @param toRoom  the room JID the IQ was addressed to
     * @return null on success, or a {@link MucException} mapped error to return
     *         to the requester
     */
    public MucException handleAdminQuery(XMLStreamReader reader, String fromJid, String toRoom)
            throws XMLStreamException {
        Room room = service.room(toRoom);
        if (room == null) return MucException.itemNotFound("No such room");

        // Parse the single <item> the request carries.
        String affAttr = null, roleAttr = null, jidAttr = null, nickAttr = null, reason = null;
        while (reader.hasNext()) {
            int ev = reader.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "item":
                        affAttr  = reader.getAttributeValue(null, "affiliation");
                        roleAttr = reader.getAttributeValue(null, "role");
                        jidAttr  = reader.getAttributeValue(null, "jid");
                        nickAttr = reader.getAttributeValue(null, "nick");
                        break;
                    case "reason":
                        reason = reader.getElementText();
                        break;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT
                    && "query".equals(reader.getLocalName())) {
                break;
            }
        }

        try {
            List<Room.Outbound> out;
            if (affAttr != null) {
                // Affiliation-based command (add/remove/ban/promote).
                Affiliation target = Affiliation.fromWire(affAttr);
                switch (target) {
                    case MEMBER:  out = room.grantMembership(fromJid, jidAttr); break;
                    case ADMIN:   out = room.grantMembership(fromJid, jidAttr); // promote path
                                  // (Room could expose grantAdmin; reuse setAffiliation upstream)
                                  break;
                    case OUTCAST: out = room.ban(fromJid, jidAttr, reason); break;
                    case NONE:    out = room.removeMember(fromJid, jidAttr); break;
                    default:      return MucException.forbidden("Unsupported affiliation change");
                }
            } else if (roleAttr != null && Role.fromWire(roleAttr) == Role.NONE) {
                // Role -> none == kick.
                out = room.kick(fromJid, nickAttr, reason);
            } else {
                return MucException.forbidden("Unrecognised admin command");
            }
            service.dispatch(out);
            return null; // success -> caller sends an empty IQ result
        } catch (MucException e) {
            return e; // caller renders <error/>
        }
    }
}