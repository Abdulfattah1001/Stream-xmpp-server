package streammessenger.group.model;

import java.time.Instant;

/**
 * A member of a group.
 *
 * No "occupant" concept - if you're a member, you're a member.
 * Whether you're currently online is determined by SessionRegistry,
 * not by any group-specific presence.
 */
public record GroupMember(
        String userId,
        String userJid,
        String displayName,    // Pulled from users table
        boolean isOwner,
        boolean isAdmin,
        Instant mutedUntil,
        Instant joinedAt,
        String addedByUserId
) {
    /**
     * Can this member perform admin actions?
     * Owner OR explicit admin.
     */
    public boolean canModerate() {
        return isOwner || isAdmin;
    }

    /**
     * Returns "owner" | "admin" | "member" for client display.
     */
    public String role() {
        if (isOwner) return "owner";
        if (isAdmin) return "admin";
        return "member";
    }
}