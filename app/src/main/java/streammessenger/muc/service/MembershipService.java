package streammessenger.muc.service;


import java.util.List;
import java.util.logging.Logger;

import streammessenger.muc.exceptions.MucException;
import streammessenger.muc.model.Affiliation;
import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.model.Occupant;
import streammessenger.muc.repository.GroupRepository;
import streammessenger.signal.SenderKeyManager;

public final class MembershipService {

    private static final Logger logger =
            Logger.getLogger(MembershipService.class.getName());

    private final GroupRepository repository;
    private final GroupRegistry registry;
    private final FanoutService fanoutService;
    private final PresenceBroadcaster presenceBroadcaster;
    private final SenderKeyManager senderKeyManager;

    public MembershipService(GroupRepository repository,
                              GroupRegistry registry,
                              FanoutService fanoutService,
                              PresenceBroadcaster presenceBroadcaster, SenderKeyManager senderKeyManager) {
        this.repository          = repository;
        this.registry            = registry;
        this.fanoutService       = fanoutService;
        this.presenceBroadcaster = presenceBroadcaster;
        this.senderKeyManager = senderKeyManager;
    }

    // =========================================================================
    // Add member via link
    // =========================================================================

    public void addMemberViaLink(String groupId, String newMemberUserId, String newMemberJid, Affiliation affiliation) {
        logger.info("Adding a member via link");
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        // Authorization

        /* TODO: Check if the group can be joined via link
            if (room.getSettings().onlyAdminsCanAdd()
                && !actor.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can add members");
        }*/


        // Already member?
        GroupRepository.MemberRecord existing = repository.getMember(groupId, newMemberUserId);
        if (existing != null
                && existing.affiliation() != Affiliation.OUTCAST) {
            throw new MucException(MucException.Code.ALREADY_MEMBER,
                    "User already a member");
        }
        if (existing != null
                && existing.affiliation() == Affiliation.OUTCAST) {
            throw new MucException(MucException.Code.BANNED,
                    "User is banned from this group");
        }

        // Capacity check
        if (room.getMemberCount() >= room.getMaxMembers()) {
            throw new MucException(MucException.Code.ROOM_FULL,
                    "Group at maximum capacity");
        }

        boolean added = repository.addMemberJoinViaLink(groupId, newMemberUserId, newMemberJid, affiliation);

        if (added) {
            room.incrementMemberCount();
            List<GroupRepository.MemberRecord> members = repository.listMembers(groupId, 500, 0);
            fanoutService.broadcastMemberAddedToGroup(room, newMemberUserId, newMemberJid, members);
            logger.info("Member added via link successfully");
        }
    }

    // =========================================================================
    // Add member
    // =========================================================================

    /**
     * Handle a new member registration
     * @param groupId The groupId
     * @param actorUserId The creator of the link
     * @param newMemberUserId
     * @param newMemberJid
     * @param affiliation
     */
    public void addMember(String groupId, String actorUserId,
                           String newMemberUserId, String newMemberJid,
                           Affiliation affiliation) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        // Authorization
        GroupRepository.MemberRecord actor = repository.getMember(groupId, actorUserId);
        if (actor == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "Actor not a member");
        }

        if (room.getSettings().onlyAdminsCanAdd()
                && !actor.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can add members");
        }

        // Can't grant equal or higher affiliation than self
        if (affiliation.ordinal() < actor.affiliation().ordinal()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Cannot grant higher affiliation than your own");
        }

        // Already member?
        GroupRepository.MemberRecord existing =
                repository.getMember(groupId, newMemberUserId);
        if (existing != null
                && existing.affiliation() != Affiliation.OUTCAST) {
            throw new MucException(MucException.Code.ALREADY_MEMBER,
                    "User already a member");
        }
        if (existing != null
                && existing.affiliation() == Affiliation.OUTCAST) {
            throw new MucException(MucException.Code.BANNED,
                    "User is banned from this group");
        }

        // Capacity check
        if (room.getMemberCount() >= room.getMaxMembers()) {
            throw new MucException(MucException.Code.ROOM_FULL,
                    "Group at maximum capacity");
        }

        boolean added = repository.addMember(groupId, newMemberUserId,
                newMemberJid, affiliation, actorUserId);

        if (added) {
            room.incrementMemberCount();
            fanoutService.broadcastMemberAdded(room,
                    newMemberUserId, newMemberJid, affiliation);

            senderKeyManager.onMemberAdded(groupId, newMemberJid);
        }
    }


    // =========================================================================
    // Remove member
    // =========================================================================

    public void removeMember(String groupId, String actorUserId,
                              String targetUserId, String reason) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        GroupRepository.MemberRecord actor =
                repository.getMember(groupId, actorUserId);
        if (actor == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "Actor not a member");
        }

        GroupRepository.MemberRecord target =
                repository.getMember(groupId, targetUserId);
        if (target == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "Target not a member");
        }

        // Self-removal always allowed
        boolean selfRemoval = actorUserId.equals(targetUserId);

        if (!selfRemoval) {
            if (!actor.affiliation().canModerate()) {
                throw new MucException(MucException.Code.NOT_AUTHORIZED,
                        "Only admins can remove members");
            }
            // Can't remove someone with equal or higher affiliation
            if (target.affiliation().ordinal()
                    <= actor.affiliation().ordinal()) {
                throw new MucException(MucException.Code.NOT_AUTHORIZED,
                        "Cannot remove someone with equal or higher rank");
            }
        }

        // Owner cannot leave - must transfer first
        if (target.affiliation() == Affiliation.OWNER) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Owner must transfer ownership before leaving");
        }

        boolean removed = repository.removeMember(groupId,
                targetUserId, actorUserId);

        if (removed) {
            room.decrementMemberCount();

            // Remove from active occupants if online
            Occupant occupant = room.removeOccupant(targetUserId);
            if (occupant != null) {
                presenceBroadcaster.broadcastLeave(room, occupant,
                        selfRemoval ? "left" : "removed", reason);
            }

            fanoutService.broadcastMemberRemoved(room,
                    targetUserId, target.userJid(), actorUserId, reason);

            senderKeyManager.onMemberRemoved(groupId, target.userJid());

        }
    }

    // =========================================================================
    // Promote / Demote
    // =========================================================================

    public void promoteToAdmin(String groupId, String actorUserId,
                                String targetUserId) {
        updateAffiliation(groupId, actorUserId, targetUserId,
                Affiliation.ADMIN);
    }

    public void demoteToMember(String groupId, String actorUserId,
                                String targetUserId) {
        updateAffiliation(groupId, actorUserId, targetUserId,
                Affiliation.MEMBER);
    }

    private void updateAffiliation(String groupId, String actorUserId,
                                    String targetUserId,
                                    Affiliation newAffiliation) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        GroupRepository.MemberRecord actor =
                repository.getMember(groupId, actorUserId);
        GroupRepository.MemberRecord target =
                repository.getMember(groupId, targetUserId);

        if (actor == null || target == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "Member not found");
        }

        if (!actor.affiliation().canModerate()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only admins can change affiliations");
        }

        // Can't promote to or above own level (except owners can do anything)
        if (actor.affiliation() != Affiliation.OWNER
                && newAffiliation.ordinal() <= actor.affiliation().ordinal()) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Cannot promote to your level or higher");
        }

        repository.updateAffiliation(groupId, targetUserId,
                newAffiliation, actorUserId);

        // Update occupant if online
        Occupant occupant = room.getOccupant(targetUserId);
        if (occupant != null) {
            Occupant updated = occupant.withAffiliation(newAffiliation);
            room.addOccupant(updated);
            presenceBroadcaster.broadcastAffiliationChange(room, updated);
        }

        fanoutService.broadcastAffiliationChange(room, targetUserId,
                target.userJid(), newAffiliation);

        // when promoting to/from OUTCAST:
        if (target.affiliation() == Affiliation.OUTCAST
                || newAffiliation == Affiliation.OUTCAST) {
            senderKeyManager.onSecurityRelevantChange(
                    groupId, "affiliation_changed_outcast");
        }
    }

    // =========================================================================
    // Transfer ownership
    // =========================================================================

    public void transferOwnership(String groupId, String currentOwnerUserId,
                                   String newOwnerUserId) {
        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            throw new MucException(MucException.Code.GROUP_NOT_FOUND,
                    "Group not found");
        }

        GroupRepository.MemberRecord owner =
                repository.getMember(groupId, currentOwnerUserId);
        if (owner == null || owner.affiliation() != Affiliation.OWNER) {
            throw new MucException(MucException.Code.NOT_AUTHORIZED,
                    "Only the owner can transfer ownership");
        }

        GroupRepository.MemberRecord newOwner =
                repository.getMember(groupId, newOwnerUserId);
        if (newOwner == null) {
            throw new MucException(MucException.Code.NOT_MEMBER,
                    "New owner must already be a member");
        }

        // Both updates must succeed atomically
        // Demote current owner first, then promote new owner
        repository.updateAffiliation(groupId, currentOwnerUserId,
                Affiliation.ADMIN, currentOwnerUserId);
        repository.updateAffiliation(groupId, newOwnerUserId,
                Affiliation.OWNER, currentOwnerUserId);

        fanoutService.broadcastOwnershipTransfer(room,
                currentOwnerUserId, newOwnerUserId);
    }
}