package streammessenger.muc;

/**
 * A participant's <b>persistent</b> relationship with a room (XEP-0045 §5.2).
 *
 * <p>Affiliation is the "membership ledger": it is stored even when the user
 * is offline/not joined. It answers "is this person allowed in, and at what
 * privilege tier?". Compare with {@link Role}, which is per-session.</p>
 */
public enum Affiliation {
    /** Created the room; can destroy it and manage admins. */
    OWNER("owner"),
    /** Can ban/grant membership but cannot destroy the room. */
    ADMIN("admin"),
    /** Allowed to join members-only rooms. ("In the group" in WhatsApp terms.) */
    MEMBER("member"),
    /** Banned. Cannot join. Highest-priority negative state. */
    OUTCAST("outcast"),
    /** No special relationship. */
    NONE("none");

    private final String wire;
    Affiliation(String wire) { this.wire = wire; }

    /** The exact token used on the wire (lowercase, stable). */
    public String wire() { return wire; }

    public static Affiliation fromWire(String s) {
        if (s == null) return NONE;
        for (Affiliation a : values()) if (a.wire.equals(s)) return a;
        return NONE;
    }

    /** Privilege ordering helper; higher = more powerful (outcast excluded). */
    public boolean atLeast(Affiliation other) {
        return rank() >= other.rank();
    }
    private int rank() {
        switch (this) {
            case OWNER:  return 3;
            case ADMIN:  return 2;
            case MEMBER: return 1;
            default:     return 0; // none / outcast have no positive privilege
        }
    }
}