package streammessenger.muc.service;


import java.time.Instant;
import java.util.logging.Logger;

import streammessenger.muc.exceptions.MucException;
import streammessenger.muc.model.Affiliation;
import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.model.GroupSettings;
import streammessenger.muc.model.GroupVisibility;
import streammessenger.muc.repository.GroupRepository;

/**
 * High-level service for group CRUD operations.
 *
 * Coordinates between:
 *   - Repository (persistence)
 *   - Registry (in-memory state)
 *   - Fanout/Presence (notifications)
 */
public final class GroupService {

    private static final Logger logger =
            Logger.getLogger(GroupService.class.getName());

    private static final int MAX_GROUP_NAME_LENGTH = 100;
    private static final int MIN_GROUP_NAME_LENGTH = 1;
    private static final int MAX_MEMBERS_HARD_LIMIT = 1024;

    private final GroupRepository repository;
    private final GroupRegistry registry;
    private final PresenceBroadcaster presenceBroadcaster;
    private final FanoutService fanoutService;

    public GroupService(GroupRepository repository,
                        GroupRegistry registry,
                        PresenceBroadcaster presenceBroadcaster,
                        FanoutService fanoutService) {
        this.repository          = repository;
        this.registry            = registry;
        this.presenceBroadcaster = presenceBroadcaster;
        this.fanoutService       = fanoutService;
    }

    // =========================================================================
    // Create
    // =========================================================================

    public GroupRoom createGroup(String name, String description,
                                 String creatorUserId,
                                 GroupVisibility visibility,
                                 int maxMembers) {
        // Validation
        if (name == null || name.isBlank()) {
            throw new MucException(MucException.Code.VALIDATION_ERROR,
                    "Group name required");
        }
        if (name.length() > MAX_GROUP_NAME_LENGTH) {
            throw new MucException(MucException.Code.VALIDATION_ERROR,
                    "Group name too long (max " + MAX_GROUP_NAME_LENGTH + ")");
        }
        if (maxMembers <= 0 || maxMembers > MAX_MEMBERS_HARD_LIMIT) {
            maxMembers = 256;
        }

        GroupRepository.GroupRecord record = repository.createGroup(
                name.trim(), description, creatorUserId, visibility, maxMembers);

        GroupRoom room = new GroupRoom(
                record.groupId(), record.jid(), record.name(),
                record.description(), null, record.creatorUserId(),
                record.visibility(), record.maxMembers(),
                record.memberCount(), GroupSettings.defaults(),
                record.createdAt()
        );

        // Cache it
        registry.getOrLoad(record.groupId());

        return room;
    }

    // =========================================================================
    // Update metadata
    // =========================================================================

    public void updateMetadata(String groupId, String actorUserId,
                                String name, String description,
                                String avatarUrl) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        // Authorization
        GroupRepository.MemberRecord member =
                repository.getMember(groupId, actorUserId);
        if (member == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "Not a member");
        }

        if (room.getSettings().onlyAdminsCanEditMeta()
                && !member.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can edit metadata");
        }

        // Validation
        if (name != null && name.length() > MAX_GROUP_NAME_LENGTH) {
            throw new MucException(MucException.Code.VALIDATION_ERROR,
                    "Name too long");
        }

        repository.updateMetadata(groupId, name, description, avatarUrl,
                actorUserId);
        room.updateMetadata(name, description, avatarUrl);

        // Notify all occupants of metadata change
        fanoutService.broadcastConfigChange(room);
    }

    // =========================================================================
    // Update settings
    // =========================================================================

    public void updateSettings(String groupId, String actorUserId,
                                GroupSettings newSettings) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        GroupRepository.MemberRecord member =
                repository.getMember(groupId, actorUserId);
        if (member == null || !member.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can change settings");
        }

        repository.updateSettings(groupId, newSettings, actorUserId);
        room.updateSettings(newSettings);

        fanoutService.broadcastConfigChange(room);
    }

    // =========================================================================
    // Destroy
    // =========================================================================

    public void destroyGroup(String groupId, String actorUserId,
                              String reason) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        GroupRepository.MemberRecord member =
                repository.getMember(groupId, actorUserId);
        if (member == null || member.affiliation() != Affiliation.OWNER) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only owner can destroy the group");
        }

        // Notify occupants before destruction
        fanoutService.broadcastDestruction(room, reason);

        // Mark deleted in DB (soft delete)
        // ... repository.softDelete(groupId)

        // Evict from memory
        registry.evict(groupId);

        logger.info("Group destroyed: " + groupId
                + " by " + actorUserId);
    }
}