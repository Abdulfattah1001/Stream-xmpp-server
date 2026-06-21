package streammessenger.muc;

/**
 * A user's PERMANENT standing with a room. Survives leaving / going offline.
 * Answers: "are they in the group, and at what privilege level?"
 */
public enum Affiliation {
    OWNER("owner"),     // created it; can do anything, including destroy
    ADMIN("admin"),     // can add/remove/ban members
    MEMBER("member"),   // is in the group
    OUTCAST("outcast"), // banned
    NONE("none");       // no relationship

    private final String wire;
    Affiliation(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static Affiliation fromWire(String s) {
        if (s == null) return NONE;
        for (Affiliation a : values()) if (a.wire.equals(s)) return a;
        return NONE;
    }

    /** Privilege comparison (outcast/none rank 0). */
    public boolean atLeast(Affiliation other) { return rank() >= other.rank(); }
    private int rank() {
        switch (this) {
            case OWNER:  return 3;
            case ADMIN:  return 2;
            case MEMBER: return 1;
            default:     return 0;
        }
    }
}