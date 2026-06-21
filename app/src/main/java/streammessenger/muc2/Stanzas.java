package streammessenger.muc;

import com.example.xmpp.muc.*;
import static com.example.xmpp.muc.Xml.esc;

/**
 * Builds every outgoing stanza as a plain string.
 *
 * Convention: 'to' is always the recipient's full JID, so the dispatcher can
 * write the returned string directly. User-supplied text is run through esc();
 * server-generated values (jids, enum wires) are already safe.
 */
public final class Stanzas {
    private Stanzas() {}

    /** Available presence: tell 'to' that 'aboutNick' is in the room. */
    public static String presence(String to, String roomJid, String aboutNick,
                                  Affiliation aff, Role role, boolean self) {
        return "<presence from='" + roomJid + "/" + aboutNick + "' to='" + to + "'>"
             +   "<x xmlns='" + Muc.NS_MUC_USER + "'>"
             +     "<item affiliation='" + aff.wire() + "' role='" + role.wire() + "'/>"
             +     (self ? "<status code='110'/>" : "")
             +   "</x>"
             + "</presence>";
    }

    /** Unavailable presence: 'aboutNick' left / was kicked (307) / banned (301) / removed (321). */
    public static String unavailable(String to, String roomJid, String aboutNick,
                                     Affiliation finalAff, boolean self,
                                     Integer statusCode, String reason) {
        return "<presence type='unavailable' from='" + roomJid + "/" + aboutNick
             +   "' to='" + to + "'>"
             +   "<x xmlns='" + Muc.NS_MUC_USER + "'>"
             +     "<item affiliation='" + finalAff.wire() + "' role='none'>"
             +       (reason != null ? "<reason>" + esc(reason) + "</reason>" : "")
             +     "</item>"
             +     (self ? "<status code='110'/>" : "")
             +     (statusCode != null ? "<status code='" + statusCode + "'/>" : "")
             +   "</x>"
             + "</presence>";
    }

    /** A group chat message copied to one recipient. */
    public static String groupMessage(String to, String fromAddr, String body) {
        return "<message type='groupchat' from='" + fromAddr + "' to='" + to + "'>"
             +   "<body>" + esc(body) + "</body>"
             + "</message>";
    }

    /** A pre-built encrypted payload (server forwards opaque content untouched). */
    public static String opaqueMessage(String to, String fromAddr, String encryptedXml) {
        return "<message type='groupchat' from='" + fromAddr + "' to='" + to + "'>"
             +   encryptedXml   // already valid XML produced by the sender's client
             + "</message>";
    }

    /** The room subject pushed to a newcomer (or after a rename). */
    public static String subject(String to, String roomJid, String subject) {
        return "<message type='groupchat' from='" + roomJid + "' to='" + to + "'>"
             +   "<subject>" + esc(subject) + "</subject>"
             + "</message>";
    }

    /** A simple IQ result. */
    public static String iqResult(String to, String id, String innerXml) {
        return "<iq type='result' id='" + id + "' to='" + to + "'>"
             +   (innerXml == null ? "" : innerXml)
             + "</iq>";
    }

    /** An IQ error from a MucException. */
    public static String iqError(String to, String id, MucException e) {
        return "<iq type='error' id='" + id + "' to='" + to + "'>"
             +   "<error type='" + e.type + "'>"
             +     "<" + e.condition + " xmlns='" + Muc.NS_STANZAS + "'/>"
             +   "</error>"
             + "</iq>";
    }

    /** A presence error (used when a join is rejected). */
    public static String presenceError(String to, String from, MucException e) {
        return "<presence type='error' from='" + from + "' to='" + to + "'>"
             +   "<error type='" + e.type + "'>"
             +     "<" + e.condition + " xmlns='" + Muc.NS_STANZAS + "'/>"
             +   "</error>"
             + "</presence>";
    }
}