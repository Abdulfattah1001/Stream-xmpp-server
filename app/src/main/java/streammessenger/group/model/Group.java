package streammessenger.group.model;

import java.time.Instant;

/**
 * Immutable snapshot of a group.
 * <p>
 * Returned by repository methods.
 * For mutations: call repository methods directly.
 */
public record Group(
        String groupId,
        String jid,
        String name,
        String description,
        String avatarUrl,
        String creatorUserId,
        GroupVisibility visibility,
        int maxMembers,
        int memberCount,
        long stateVersion,
        Instant createdAt
) {}