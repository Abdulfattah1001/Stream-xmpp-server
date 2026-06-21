package streammessenger.group.model;


import java.time.Instant;

/**
 * Represents one change to group state.
 * <p>
 * Stored in group_state_events table.
 * Replayed to clients during delta sync.
 * <p>
 * Event types:
 *   group_created       - first event, payload has initial group state
 *   member_added        - someone was added to the group
 *   member_removed      - someone was removed by an admin
 *   member_left         - someone left voluntarily
 *   admin_granted       - member became admin
 *   admin_revoked       - admin became member
 *   owner_transferred   - ownership moved to different user
 *   metadata_changed    - name, description, or avatar changed
 *   settings_changed    - group settings changed
 *   group_destroyed     - group was destroyed by owner
 */
public record GroupStateEvent(
        String eventId,
        String groupId,
        long stateVersion,
        String eventType,
        String actorUserId,
        String targetUserId,
        String payloadJson,
        Instant createdAt
) {}