package streammessenger.muc.model;

/**
 * XEP-0045 §5.2 Affiliations.
 *
 * Affiliation is the PERSISTENT relationship between a user and a room.
 * It survives the user leaving and rejoining.
 *
 * Hierarchy: owner > admin > member > none > outcast
 */
public enum Affiliation {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member"),
    NONE("none"),
    OUTCAST("outcast");

    private final String xmlValue;

    Affiliation(String xmlValue) { this.xmlValue = xmlValue; }

    public String xmlValue() { return xmlValue; }

    public boolean isAtLeast(Affiliation other) {
        return ordinal() <= other.ordinal();
    }

    public boolean canModerate() {
        return this == OWNER || this == ADMIN;
    }

    public boolean canSendMessages(boolean announcementMode) {
        if (this == OUTCAST) return false;
        if (announcementMode) return canModerate();
        return this != OUTCAST;
    }

    public static Affiliation fromXml(String value) {
        if (value == null) return NONE;
        for (Affiliation a : values()) {
            if (a.xmlValue.equalsIgnoreCase(value)) return a;
        }
        return NONE;
    }
}