package streammessenger.muc;

/** Immutable room policy + display metadata. Use builder or whatsappStyle(). */
public final class RoomConfig {
    private final String name;        // human display name (editable metadata)
    private final String description;
    private final boolean membersOnly;
    private final boolean persistent;
    private final boolean moderated;
    private final boolean nonAnonymous;
    private final int maxOccupants;
    private final int historyOnJoin;  // how many past messages to replay

    private RoomConfig(String name, String description, boolean membersOnly,
                       boolean persistent, boolean moderated, boolean nonAnonymous,
                       int maxOccupants, int historyOnJoin) {
        this.name = name; this.description = description;
        this.membersOnly = membersOnly; this.persistent = persistent;
        this.moderated = moderated; this.nonAnonymous = nonAnonymous;
        this.maxOccupants = maxOccupants; this.historyOnJoin = historyOnJoin;
    }

    public String name()         { return name; }
    public String description()  { return description; }
    public boolean membersOnly() { return membersOnly; }
    public boolean persistent()  { return persistent; }
    public boolean moderated()   { return moderated; }
    public boolean nonAnonymous(){ return nonAnonymous; }
    public int maxOccupants()    { return maxOccupants; }
    public int historyOnJoin()   { return historyOnJoin; }

    /** Return a copy with a new display name (used at creation / rename). */
    public RoomConfig withName(String newName) {
        return new RoomConfig(newName, description, membersOnly, persistent,
                moderated, nonAnonymous, maxOccupants, historyOnJoin);
    }

    /** Closest analogue to a WhatsApp group. */
    public static RoomConfig whatsappStyle() {
        return new RoomConfig("", "", true, true, false, true, 1024, 20);
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String name = "", description = "";
        private boolean membersOnly = true, persistent = true,
                        moderated = false, nonAnonymous = true;
        private int maxOccupants = 256, historyOnJoin = 20;
        public Builder name(String v)         { name = v; return this; }
        public Builder description(String v)  { description = v; return this; }
        public Builder membersOnly(boolean v) { membersOnly = v; return this; }
        public Builder persistent(boolean v)  { persistent = v; return this; }
        public Builder moderated(boolean v)   { moderated = v; return this; }
        public Builder nonAnonymous(boolean v){ nonAnonymous = v; return this; }
        public Builder maxOccupants(int v)    { maxOccupants = v; return this; }
        public Builder historyOnJoin(int v)   { historyOnJoin = v; return this; }
        public RoomConfig build() {
            return new RoomConfig(name, description, membersOnly, persistent,
                    moderated, nonAnonymous, maxOccupants, historyOnJoin);
        }
    }
}