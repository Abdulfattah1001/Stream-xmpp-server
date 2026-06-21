package streammessenger.group.model;

public enum GroupVisibility {
    PUBLIC,
    PRIVATE;

    public String xmlValue() { return name().toLowerCase(); }

    public static GroupVisibility fromString(String s) {
        if (s == null) return PRIVATE;
        try { return GroupVisibility.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return PRIVATE; }
    }
}