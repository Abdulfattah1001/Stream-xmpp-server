package streammessenger.muc.model;

public enum GroupVisibility {
    PUBLIC,    // Discoverable, anyone can find via search
    PRIVATE,   // Hidden from search, joinable only via invite/link
    HIDDEN;    // Not discoverable at all, no public exposure

    public String xmlValue() { return name().toLowerCase(); }

    public static GroupVisibility fromString(String s) {
        if (s == null) return PRIVATE;
        try { return GroupVisibility.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return PRIVATE; }
    }
}