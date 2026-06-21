package streammessenger.muc;

import com.example.xmpp.muc.Affiliation;
import com.example.xmpp.muc.Role;

/** A user CURRENTLY present in a room (has a live session). Nick == their uid. */
public final class Occupant {
    private final String realJid;   // full JID, e.g. alice@example.com/phone
    private final String roomJid;   // <uuid>@conference.example.com
    private final String nick;      // the user's uid (stable)
    private volatile Role role;
    private volatile Affiliation affiliation;

    public Occupant(String realJid, String roomJid, String nick, Role role, Affiliation aff) {
        this.realJid = realJid; this.roomJid = roomJid; this.nick = nick;
        this.role = role; this.affiliation = aff;
    }
    public String realJid()        { return realJid; }
    public String nick()           { return nick; }
    public Role role()             { return role; }
    public Affiliation affiliation(){ return affiliation; }
    void setRole(Role r)           { this.role = r; }
    void setAffiliation(Affiliation a) { this.affiliation = a; }
}