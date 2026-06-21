package streammessenger.muc;

/**
 * A participant's <b>transient</b> capabilities within a room (XEP-0045 §5.1).
 *
 * <p>Role exists only while the occupant is present. When they leave, role
 * resets to {@link #NONE}. Roles are typically derived from affiliation on
 * join (e.g. an {@code owner} affiliation usually maps to {@code moderator}
 * role), but a moderator can temporarily change someone's role without
 * touching their long-term affiliation.</p>
 */
public enum Role {
    /** Can kick, grant voice, change subject. */
    MODERATOR("moderator"),
    /** Can speak. Normal member. */
    PARTICIPANT("participant"),
    /** Present but muted (read-only). */
    VISITOR("visitor"),
    /** Not in the room. */
    NONE("none");

    private final String wire;
    Role(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static Role fromWire(String s) {
        if (s == null) return NONE;
        for (Role r : values()) if (r.wire.equals(s)) return r;
        return NONE;
    }
}