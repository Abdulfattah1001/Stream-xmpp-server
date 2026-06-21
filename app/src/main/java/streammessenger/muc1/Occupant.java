package streammessenger.muc;

/**
 * A user who is currently *present* in a room (has an active session).
 *
 * <p>Distinct from "member": a member who is offline has an affiliation entry
 * but no Occupant. An anonymous visitor in a public room has an Occupant but
 * affiliation {@code none}.</p>
 *
 * <p>Identity note: the {@code occupantJid} is {@code room@service/nick}; the
 * {@code realJid} is the user's actual full JID. We never leak {@code realJid}
 * to other occupants in anonymous rooms.</p>
 */
public final class Occupant {
    private final String realJid;       // e.g. alice@example.com/phone
    private final String roomJid;       // e.g. room@conference.example.com
    private volatile String nick;       // resource part used in the room
    private volatile Role role;
    private volatile Affiliation affiliation;

    public Occupant(String realJid, String roomJid, String nick,
                    Role role, Affiliation affiliation) {
        this.realJid = realJid;
        this.roomJid = roomJid;
        this.nick = nick;
        this.role = role;
        this.affiliation = affiliation;
    }

    public String realJid()       { return realJid; }
    /** The occupant's in-room address: room@service/nick. */
    public String occupantJid()   { return roomJid + "/" + nick; }
    public String nick()          { return nick; }
    public Role role()            { return role; }
    public Affiliation affiliation(){ return affiliation; }

    void setNick(String n)        { this.nick = n; }
    void setRole(Role r)          { this.role = r; }
    void setAffiliation(Affiliation a) { this.affiliation = a; }
}