package streammessenger.muc1;

/**
 * Immutable room policy. WhatsApp-style groups map cleanly onto a specific
 * preset: persistent + members-only + (optionally) non-anonymous + moderated.
 *
 * <p>Use {@link #whatsappStyle()} for the closest analogue to a WhatsApp group.</p>
 */
public final class RoomConfig {
    private final boolean persistent;
    private final boolean membersOnly;
    private final boolean moderated;
    private final boolean nonAnonymous;
    private final int maxOccupants;

    private RoomConfig(boolean p, boolean m, boolean mod, boolean na, int max) {
        this.persistent = p; this.membersOnly = m;
        this.moderated = mod; this.nonAnonymous = na; this.maxOccupants = max;
    }

    public boolean persistent()    { return persistent; }
    public boolean membersOnly()   { return membersOnly; }
    public boolean moderated()     { return moderated; }
    public boolean nonAnonymous()  { return nonAnonymous; }
    public int maxOccupants()      { return maxOccupants; }

    /** Closest analogue to a WhatsApp group. */
    public static RoomConfig whatsappStyle() {
        // members-only (can't randomly walk in), persistent (survives empty),
        // non-anonymous (members see each other), not moderated (everyone speaks).
        return new RoomConfig(true, true, false, true, 1024);
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private boolean p = true, m = true, mod = false, na = true;
        private int max = 256;
        public Builder persistent(boolean v)   { p = v; return this; }
        public Builder membersOnly(boolean v)  { m = v; return this; }
        public Builder moderated(boolean v)    { mod = v; return this; }
        public Builder nonAnonymous(boolean v) { na = v; return this; }
        public Builder maxOccupants(int v)     { max = v; return this; }
        public RoomConfig build() { return new RoomConfig(p, m, mod, na, max); }
    }
}