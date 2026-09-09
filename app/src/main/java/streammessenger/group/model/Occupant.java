package streammessenger.group.model;

import java.time.Instant;

/**
 * A user currently online in a group room.
 *
 * Created when user sends presence to room.
 * Destroyed when user leaves or disconnects.
 *
 * Immutable. To change role/affiliation: remove old, add new.
 */
public record Occupant(
        String userId,
        String userJid,
        String nickname,
        String sessionUid,
        Affiliation affiliation,
        Role role,
        Instant joinedAt
) {
    /**
     * Returns the room-scoped JID for this occupant.
     * Format: groupJid/nickname
     * e.g. "g_abc123@conference.domain.com/Alice"
     */
    public String roomJid(String groupJid) {
        return groupJid + "/" + nickname;
    }

    public Occupant withRole(Role newRole) {
        return new Occupant(userId, userJid, nickname, sessionUid,
                affiliation, newRole, joinedAt);
    }

    public Occupant withAffiliation(Affiliation newAffiliation) {
        Role newRole = Role.fromAffiliation(newAffiliation, false);
        return new Occupant(userId, userJid, nickname, sessionUid,
                newAffiliation, newRole, joinedAt);
    }
}