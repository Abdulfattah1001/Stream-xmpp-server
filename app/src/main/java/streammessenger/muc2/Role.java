package streammessenger.muc;

/**
 * A user's TEMPORARY powers WHILE present in a room. Resets to NONE on leave.
 * Answers: "can they speak / moderate right now?"
 */
public enum Role {
    MODERATOR("moderator"),     // can kick, change subject
    PARTICIPANT("participant"), // can speak
    VISITOR("visitor"),         // present but muted
    NONE("none");               // not in the room

    private final String wire;
    Role(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static Role fromWire(String s) {
        if (s == null) return NONE;
        for (Role r : values()) if (r.wire.equals(s)) return r;
        return NONE;
    }
}