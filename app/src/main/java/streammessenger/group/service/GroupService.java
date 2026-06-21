package streammessenger.group.service;

import streammessenger.group.model.*;
import streammessenger.group.repository.GroupRepository;

import java.util.logging.Logger;

/**
 * Coordinates between repository (persistence) and notifier (events).
 * <p>
 * Every state-changing operation:
 *   1. Validates authorization
 *   2. Persists the change (which increments state_version)
 *   3. Notifies online members of the change
 * <p>
 * Authorization checks are explicit and consistent.
 */
public final class GroupService {

    private static final Logger logger =
            Logger.getLogger(GroupService.class.getName());

    private final GroupRepository repository;
    private final GroupEventNotifier notifier;

    public GroupService(GroupRepository repository,
                         GroupEventNotifier notifier) {
        this.repository = repository;
        this.notifier   = notifier;
    }

    // =========================================================================
    // Create group
    // =========================================================================

    public Group createGroup(String name, String description,
                              String creatorUserId, String creatorJid,
                              GroupVisibility visibility, int maxMembers) {
        if (name == null || name.isBlank()) {
            throw new GroupException(GroupException.Code.VALIDATION,
                    "Group name required");
        }
        if (maxMembers <= 0 || maxMembers > 1024) maxMembers = 256;

        return repository.create(name.trim(), description,
                creatorUserId, creatorJid, visibility, maxMembers);
    }

    // =========================================================================
    // Add member
    // =========================================================================

    public void addMember(String groupId, String actorUserId,
                           String newUserId, String newUserJid) {

        Group group = repository.get(groupId);
        if (group == null) {
            throw new GroupException(GroupException.Code.NOT_FOUND,
                    "Group not found");
        }

        // Authorization
        GroupMember actor = repository.getMember(groupId, actorUserId);
        if (actor == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "Not a member of this group");
        }

        // TODO: This has to be validated also on the client side
        GroupSettings settings = repository.getSettings(groupId);
        if (settings.onlyAdminsCanAdd() && !actor.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can add members");
        }

        // Already a member?
        if (repository.isMember(groupId, newUserId)) {
            throw new GroupException(GroupException.Code.ALREADY_MEMBER,
                    "User is already a member");
        }

        // Capacity check
        if (group.memberCount() >= group.maxMembers()) {
            throw new GroupException(GroupException.Code.GROUP_FULL,
                    "Group is at maximum capacity");
        }

        // Add (always as regular member, not admin)
        long newVersion = repository.addMember(
                groupId, newUserId, newUserJid, actorUserId, false);

        // Build member object for notification
        GroupMember newMember = repository.getMember(groupId, newUserId);
        if (newMember != null) {
            notifier.notifyMemberAdded(groupId, newVersion,
                    actorUserId, newMember);
        }

        logger.info("Member added: groupId=" + groupId
                + " user=" + newUserId + " by=" + actorUserId);
    }

    // =========================================================================
    // Join via invite link
    // =========================================================================

    /**
     * Joins a group via shareable invite link.
     * <p>
     * No admin involvement - this is the user choosing to join themselves
     * by clicking a link someone shared with them.
     * <p>
     * Steps:
     *   1. Validate link, check capacity, add member (atomic in repo)
     *   2. Notify all existing members
     *   3. Send full snapshot to new joiner
     *
     * @return The group_id they joined
     * @throws GroupException if link invalid, group full, or already member
     */
    public String joinViaLink(String linkToken, String newUserId,
                              String newUserJid) {

        GroupRepository.LinkJoinResult result =
                repository.joinViaLink(linkToken, newUserId, newUserJid);

        if (result == null) {
            throw new GroupException(GroupException.Code.NOT_FOUND,
                    "Invalid or revoked invite link");
        }

        switch (result.status()) {
            case ALREADY_MEMBER ->
                    throw new GroupException(GroupException.Code.ALREADY_MEMBER,
                            "You are already a member of this group");

            case GROUP_FULL ->
                    throw new GroupException(GroupException.Code.GROUP_FULL,
                            "This group is at maximum capacity");

            case INVALID_LINK ->
                    throw new GroupException(GroupException.Code.NOT_FOUND,
                            "Invalid invite link");

            case SUCCESS -> {
                // Get the new member's full record (with display_name from users table)
                GroupMember newMember = repository.getMember(
                        result.groupId(), newUserId);

                if (newMember != null) {
                    notifier.notifyMemberJoinedViaLink(
                            result.groupId(),
                            result.newVersion(),
                            newMember,
                            linkToken
                    );
                }

                return result.groupId();
            }
        }

        // Unreachable
        throw new GroupException(GroupException.Code.INTERNAL,
                "Unknown link join status");
    }

    /**
     * Creates a new invite link for a group.
     * Only admins can create links.
     */
    public GroupRepository.InviteLink createInviteLink(String groupId, String actorUserId) {
        GroupMember actor = repository.getMember(groupId, actorUserId);
        if (actor == null || !actor.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can create invite links");
        }

        return repository.createInviteLink(groupId, actorUserId);
    }

    /**
     * Revokes an existing invite link.
     */
    public void revokeInviteLink(String groupId, String actorUserId,
                                 String linkToken) {
        GroupMember actor = repository.getMember(groupId, actorUserId);
        if (actor == null || !actor.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can revoke invite links");
        }

        repository.revokeInviteLink(linkToken, actorUserId);
    }

    // =========================================================================
    // Remove member
    // =========================================================================

    public void removeMember(String groupId, String actorUserId,
                              String targetUserId) {

        GroupMember actor = repository.getMember(groupId, actorUserId);
        GroupMember target = repository.getMember(groupId, targetUserId);

        if (actor == null || target == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "Member not found");
        }

        boolean isSelfLeave = actorUserId.equals(targetUserId);

        if (!isSelfLeave) {
            if (!actor.canModerate()) {
                throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                        "Only admins can remove members");
            }
            if (target.isOwner()) {
                throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                        "Cannot remove the owner");
            }
            if (target.isAdmin() && !actor.isOwner()) {
                throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                        "Only owner can remove admins");
            }
        } else {
            if (target.isOwner()) {
                throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                        "Owner must transfer ownership before leaving");
            }
        }

        long newVersion = repository.removeMember(
                groupId, targetUserId, actorUserId, isSelfLeave);

        if (newVersion > 0) {
            notifier.notifyMemberRemoved(groupId, newVersion,
                    actorUserId, targetUserId, target.userJid(), isSelfLeave);
        }
    }

    // =========================================================================
    // Grant/revoke admin
    // =========================================================================

    public void grantAdmin(String groupId, String actorUserId,
                            String targetUserId) {
        GroupMember actor = repository.getMember(groupId, actorUserId);
        GroupMember target = repository.getMember(groupId, targetUserId);

        if (actor == null || target == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "Member not found");
        }

        if (!actor.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can grant admin status");
        }

        if (target.isAdmin() || target.isOwner()) {
            return; // already admin/owner - no-op
        }

        long newVersion = repository.grantAdmin(
                groupId, targetUserId, actorUserId);

        if (newVersion > 0) {
            notifier.notifyAdminChange(groupId, newVersion,
                    actorUserId, targetUserId, target.userJid(), true);
        }
    }

    public void revokeAdmin(String groupId, String actorUserId,
                             String targetUserId) {
        GroupMember actor = repository.getMember(groupId, actorUserId);
        GroupMember target = repository.getMember(groupId, targetUserId);

        if (actor == null || target == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "Member not found");
        }

        if (!actor.isOwner()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only owner can revoke admin status");
        }

        if (target.isOwner()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Cannot revoke owner's admin status");
        }

        if (!target.isAdmin()) return; // not admin - no-op

        long newVersion = repository.revokeAdmin(
                groupId, targetUserId, actorUserId);

        if (newVersion > 0) {
            notifier.notifyAdminChange(groupId, newVersion,
                    actorUserId, targetUserId, target.userJid(), false);
        }
    }

    // =========================================================================
    // Transfer ownership
    // =========================================================================

    public void transferOwnership(String groupId, String currentOwnerUserId,
                                    String newOwnerUserId) {
        GroupMember owner = repository.getMember(groupId, currentOwnerUserId);
        if (owner == null || !owner.isOwner()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only the current owner can transfer ownership");
        }

        GroupMember newOwner = repository.getMember(groupId, newOwnerUserId);
        if (newOwner == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "New owner must already be a member");
        }

        long newVersion = repository.transferOwnership(
                groupId, currentOwnerUserId, newOwnerUserId);

        if (newVersion > 0) {
            notifier.notifyOwnershipTransfer(groupId, newVersion,
                    currentOwnerUserId, newOwnerUserId);
        }
    }

    // =========================================================================
    // Update metadata
    // =========================================================================

    public void updateMetadata(String groupId, String actorUserId,
                                String name, String description,
                                String avatarUrl) {

        GroupMember actor = repository.getMember(groupId, actorUserId);
        if (actor == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "Not a member");
        }

        GroupSettings settings = repository.getSettings(groupId);
        if (settings.onlyAdminsCanEditInfo() && !actor.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can edit group info");
        }

        if (name != null && name.length() > 100) {
            throw new GroupException(GroupException.Code.VALIDATION,
                    "Name too long");
        }
        if (description != null && description.length() > 500) {
            throw new GroupException(GroupException.Code.VALIDATION,
                    "Description too long");
        }

        long newVersion = repository.updateMetadata(
                groupId, actorUserId, name, description, avatarUrl);

        if (newVersion > 0) {
            notifier.notifyMetadataChanged(groupId, newVersion,
                    actorUserId, name, description, avatarUrl);
        }
    }

    // =========================================================================
    // Update settings
    // =========================================================================

    public void updateSettings(String groupId, String actorUserId,
                                GroupSettings settings) {
        GroupMember actor = repository.getMember(groupId, actorUserId);
        if (actor == null || !actor.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can change settings");
        }

        long newVersion = repository.updateSettings(
                groupId, actorUserId, settings);

        if (newVersion > 0) {
            notifier.notifySettingsChanged(groupId, newVersion,
                    actorUserId, settings);
        }
    }
}