package streammessenger.muc.model;

/**
 * XEP-0045 §5.1 Roles.
 *
 * Role is the TEMPORARY runtime state of an occupant in a room.
 * Computed from affiliation when user joins.
 * Lost when user leaves the room.
 */
public enum Role {
    MODERATOR("moderator"),
    PARTICIPANT("participant"),
    VISITOR("visitor"),
    NONE("none");

    private final String xmlValue;

    Role(String xmlValue) { this.xmlValue = xmlValue; }

    public String xmlValue() { return xmlValue; }

    /**
     * XEP-0045 §5.1.2: Default role mapping from affiliation.
     */
    public static Role fromAffiliation(Affiliation aff, boolean moderated) {
        return switch (aff) {
            case OWNER, ADMIN -> MODERATOR;
            case MEMBER -> PARTICIPANT;
            case NONE -> moderated ? VISITOR : PARTICIPANT;
            case OUTCAST -> NONE;
        };
    }

    public boolean canSpeak() {
        return this == MODERATOR || this == PARTICIPANT;
    }

    public boolean canModerate() {
        return this == MODERATOR;
    }
}