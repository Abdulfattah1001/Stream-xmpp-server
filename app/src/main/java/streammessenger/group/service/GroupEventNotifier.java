package streammessenger.group.service;

import streammessenger.group.model.*;
import streammessenger.group.repository.GroupRepository;
import streammessenger.muc.model.GroupEventType;
import streammessenger.muc.model.GroupSystemEvent;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Notifies online group members of state changes in real-time.
 * <p>
 * KEY CONCEPT: This is INCREMENTAL push.
 * We don't push the full group state on every change.
 * We push the EVENT, which the client applies to its local state.
 * <p>
 * For offline users: they catch up via delta sync on next connect.
 * No "messages waiting" - they just sync from their last known version.
 */
@SuppressWarnings("unused")
public final class GroupEventNotifier {

    private static final Logger logger =
            Logger.getLogger(GroupEventNotifier.class.getName());

    private static final String GROUP_NS = "urn:xmpp:group:0";

    private final GroupRepository repository;
    private final SessionRegistry sessionRegistry;

    private final ExecutorService notificationPool = new ThreadPoolExecutor(
            10, 50, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(5000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public GroupEventNotifier(GroupRepository repository,
                                SessionRegistry sessionRegistry) {
        this.repository      = repository;
        this.sessionRegistry = sessionRegistry;
    }

    // =========================================================================
    // Notification: someone added a new member
    // =========================================================================

    /**
     * Two notifications happen:
     * <p>
     * 1. To existing members:
     *    <message><event xmlns='urn:xmpp:group:0' type='member_added'>
     *      <group_id>...</group_id>
     *      <version>6</version>
     *      <actor>alice_uid</actor>
     *      <member jid='bob' display_name='Bob' is_admin='false'/>
     *    </event></message>
     * <p>
     * 2. To the new member:
     *    The full group snapshot (since they have nothing to sync from):
     *    <message><snapshot xmlns='urn:xmpp:group:0'>
     *      <group ... />
     *      <settings .../>
     *      <members>
     *        <member jid='alice' ... />
     *        <member jid='bob' ... />
     *        ...
     *      </members>
     *    </snapshot></message>
     */
    public void notifyMemberAdded(String groupId, long version,
                                    String addedByUserId,
                                    GroupMember newMember) {

        Group group = repository.get(groupId);
        if (group == null) return;

        // Build event for existing members
        String memberAddedEvent = String.format(
            "<message from='%s'>" +
            "<event xmlns='%s' type='member_added'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<actor>%s</actor>" +
            "<member jid='%s' display_name='%s' is_admin='%b'/>" +
            "</event></message>",
            escapeXml(group.jid()),
            GROUP_NS,
            group.groupId(),
            version,
            escapeXml(addedByUserId),
            escapeXml(newMember.userJid()),
            escapeXml(newMember.displayName() != null
                    ? newMember.displayName() : ""),
            newMember.isAdmin()
        );

        // Fan out to existing members (excluding new member)
        List<String> memberJids = repository.listMemberJids(groupId);
        for (String jid : memberJids) {
            if (jid.equals(newMember.userJid())) continue;
            deliverToMember(jid, memberAddedEvent);
        }

        // Send full snapshot to new member
        sendFullSnapshot(group, newMember.userJid());
    }

    // =========================================================================
    // Notification: someone joined via invite link
    // =========================================================================

    /**
     * Slightly different from notifyMemberAdded because:
     *   1. No "actor" - the new member is the actor
     *   2. Different event type so clients can show
     *      "Alice joined via invite link" instead of
     *      "Bob added Alice"
     * <p>
     * Same flow otherwise:
     *   - Existing members get the event (incremental update)
     *   - New member gets the full snapshot (initial state)
     */
    public void notifyMemberJoinedViaLink(String groupId, long version,
                                          GroupMember newMember,
                                          String linkToken) {
        Group group = repository.get(groupId);
        if (group == null) return;

        // Event for existing members - note: actor is the new member
        // themselves, not someone else
        String memberJoinedEvent = String.format(
                "<message from='%s' type='groupchat'>" +
                        "<event xmlns='%s' type='member_joined_via_link'>" +
                        "<group_id>%s</group_id>" +
                        "<version>%d</version>" +
                        "<member jid='%s' user_id='%s' display_name='%s' display_status='%s' phone_number='%s'" +
                        " is_admin='false' joined_via='link'/>" +
                        "</event></message>",
                escapeXml(group.jid()),
                GROUP_NS,
                group.groupId(),
                version,
                escapeXml(newMember.userJid()),
                escapeXml(newMember.userId()),
                escapeXml(newMember.displayName() != null
                        ? newMember.displayName() : ""),
                escapeXml(newMember.displayStatus()),
                escapeXml(newMember.phoneNumber())
        );

        // Send to all existing members EXCEPT the new one
        List<String> memberJids = repository.listMemberJids(groupId);
        for (String jid : memberJids) {
            if (jid.equals(newMember.userJid())) continue;
            deliverToMember(jid, memberJoinedEvent);
        }

        // Send full snapshot (including members list) to the new joiner
        logger.info("Sending full snapshot after joining via link");
        sendFullSnapshot(group, newMember.userJid());

        logger.info("Notified " + (memberJids.size() - 1)
                + " existing members + sent snapshot to new joiner "
                + newMember.userJid());
    }

    // =========================================================================
    // Notification: member removed (by admin or self-leave)
    // =========================================================================

    public void notifyMemberRemoved(String groupId, long version,
                                      String actorUserId,
                                      String removedUserId,
                                      String removedUserJid,
                                      boolean voluntary) {

        Group group = repository.get(groupId);
        if (group == null) return;

        String eventType = voluntary ? "member_left" : "member_removed";

        String event = String.format(
            "<message from='%s'>" +
            "<event xmlns='%s' type='%s'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<actor>%s</actor>" +
            "<user_id>%s</user_id>" +
            "<user_jid>%s</user_jid>" +
            "</event></message>",
            escapeXml(group.jid()),
            GROUP_NS,
            eventType,
            group.groupId(),
            version,
            escapeXml(actorUserId),
            escapeXml(removedUserId),
            escapeXml(removedUserJid)
        );

        // Send to remaining members
        List<String> memberJids = repository.listMemberJids(groupId);
        for (String jid : memberJids) {
            deliverToMember(jid, event);
        }

        // Also notify the removed user (they need to know to clean up local state)
        if (!voluntary) {
            String removedNotice = String.format(
                "<message from='%s'>" +
                "<event xmlns='%s' type='you_were_removed'>" +
                "<group_id>%s</group_id>" +
                "<actor>%s</actor>" +
                "</event></message>",
                escapeXml(group.jid()),
                GROUP_NS,
                group.groupId(),
                escapeXml(actorUserId)
            );
            deliverToMember(removedUserJid, removedNotice);
        }
    }

    // =========================================================================
    // Notification: admin status changed
    // =========================================================================

    public void notifyAdminChange(String groupId, long version,
                                    String actorUserId,
                                    String targetUserId, String targetUserJid,
                                    boolean granted) {

        Group group = repository.get(groupId);
        if (group == null) return;

        String eventType = granted ? "admin_granted" : "admin_revoked";

        String event = String.format(
            "<message from='%s'>" +
            "<event xmlns='%s' type='%s'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<actor>%s</actor>" +
            "<user_id>%s</user_id>" +
            "<user_jid>%s</user_jid>" +
            "</event></message>",
            escapeXml(group.jid()),
            GROUP_NS,
            eventType,
            group.groupId(),
            version,
            escapeXml(actorUserId),
            escapeXml(targetUserId),
            escapeXml(targetUserJid)
        );

        broadcastToAllMembers(groupId, event);
    }

    // =========================================================================
    // Notification: ownership transferred
    // =========================================================================

    public void notifyOwnershipTransfer(String groupId, long version,
                                         String oldOwnerUserId,
                                         String newOwnerUserId) {
        Group group = repository.get(groupId);
        if (group == null) return;

        String event = String.format(
            "<message from='%s'>" +
            "<event xmlns='%s' type='owner_transferred'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<from>%s</from>" +
            "<to>%s</to>" +
            "</event></message>",
            escapeXml(group.jid()),
            GROUP_NS,
            group.groupId(),
            version,
            escapeXml(oldOwnerUserId),
            escapeXml(newOwnerUserId)
        );

        broadcastToAllMembers(groupId, event);
    }

    // =========================================================================
    // Notification: metadata changed (name, description, avatar)
    // =========================================================================

    public void notifyMetadataChanged(String groupId, long version,
                                        String actorUserId,
                                        String name, String description,
                                        String avatarUrl) {
        logger.info("Notifying members of the group of the changes made");
        Group group = repository.get(groupId);
        if (group == null) return;

        StringBuilder fields = new StringBuilder();
        if (name != null) {
            fields.append("<name>").append(escapeXml(name)).append("</name>");
        }
        if (description != null) {
            fields.append("<description>").append(escapeXml(description))
                  .append("</description>");
        }
        if (avatarUrl != null) {
            fields.append("<avatar_url>").append(escapeXml(avatarUrl))
                  .append("</avatar_url>");
        }

        GroupSystemEvent systemEvent = new GroupSystemEvent(GroupEventType.DESCRIPTION_CHANGED, actorUserId, null, Instant.now());


        String response = String.format(
                "<message id='%s' from='%s' type='groupchat'>" +
                        "<system xmlns='urn:xmpp:group:0'>" +
                            "<event type='%s' actor='%s' subject='%s'/>" +
                        "</system>" +
                   "</message>",
                UUID.randomUUID(), groupId+"/"+actorUserId, systemEvent.type(), systemEvent.actorId(), systemEvent.subjectId());

        /*String event = String.format(
            "<message from='%s'>" +
            "<event xmlns='%s' type='metadata_changed'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<actor>%s</actor>" +
            "%s" +
            "</event></message>",
            escapeXml(group.jid()),
            GROUP_NS,
            group.groupId(),
            version,
            escapeXml(actorUserId),
            fields
        );

        broadcastToAllMembers(groupId, event);*/
        broadcastToAllMembers(groupId, response);
    }

    // ========================================================================
    // Notification: settings changed
    // =========================================================================

    public void notifySettingsChanged(String groupId, long version,
                                        String actorUserId,
                                        GroupSettings settings) {
        Group group = repository.get(groupId);
        if (group == null) return;

        String event = String.format(
            "<message from='%s'>" +
            "<event xmlns='%s' type='settings_changed'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<actor>%s</actor>" +
            "<settings>" +
            "<only_admins_can_send>%b</only_admins_can_send>" +
            "<only_admins_can_edit_info>%b</only_admins_can_edit_info>" +
            "<only_admins_can_add>%b</only_admins_can_add>" +
            "<disappearing_seconds>%d</disappearing_seconds>" +
            "<approval_required>%b</approval_required>" +
            "</settings>" +
            "</event></message>",
            escapeXml(group.jid()),
            GROUP_NS,
            group.groupId(),
            version,
            escapeXml(actorUserId),
            settings.onlyAdminsCanSend(),
            settings.onlyAdminsCanEditInfo(),
            settings.onlyAdminsCanAdd(),
            settings.disappearingSeconds(),
            settings.approvalRequired()
        );

        broadcastToAllMembers(groupId, event);
    }

    public void notifyOnlyAdminCanSendSettings(String groupId, String actorUserId, boolean state) {
        Group group = repository.get(groupId);
        if (group == null) return;

        long groupVersion = group.stateVersion();

        String event = String.format(
                "<message id='%s' from='%s' type='groupchat'>" +
                        "<system xmlns='urn:xmpp:group:0' timestamp='%s'>" +
                            "<event type='only_admins_can_send_message' actor='%s' subject='%s'>" +
                                "<state>%b</state>" +
                            "</event>" +
                        "</system>" +
                  "</event></message>",
                UUID.randomUUID().toString(),
                escapeXml(group.jid()+"/"+actorUserId),
                System.currentTimeMillis(),
                actorUserId,
                null,
                state
        );

        broadcastToAllMembers(groupId, event);
    }

    public void notifyOnlyAdminCanAddSettings(String groupId, String actorUserId, boolean state) {
        Group group = repository.get(groupId);
        if (group == null) return;

        String event = String.format(
                "<message id='%s' from='%s' type='groupchat'>" +
                        "<system xmlns='urn:xmpp:group:0' timestamp='%s'>" +
                            "<event type='only_admins_can_add' actor='%s' subject='%s'>" +
                                "<state>%b</state>" +
                        "</event>" +
                        "</system>" +
                  "</event></message>",
                UUID.randomUUID().toString(),
                escapeXml(group.jid()+"/"+actorUserId),
                System.currentTimeMillis(),
                actorUserId,
                null,
                state
        );

        broadcastToAllMembers(groupId, event);
    }

    public void notifyOnlyAdminCanEditSettings(String groupId, String actorUserId, boolean state) {
        Group group = repository.get(groupId);
        if (group == null) return;
        String event = String.format(
                "<message id='%s' from='%s' type='groupchat'>" +
                        "<system xmlns='urn:xmpp:group:0' timestamp='%s'>" +
                            "<event type='only_admins_can_edit' actor='%s' subject='%s'>" +
                                "<state>%b</state>" +
                            "</event>" +
                        "</system>" +
                  "</event></message>",
                UUID.randomUUID().toString(),
                escapeXml(group.jid()+"/"+actorUserId),
                System.currentTimeMillis(),
                actorUserId,
                null,
                state
        );

        broadcastToAllMembers(groupId, event);
    }

    // =========================================================================
    // Full snapshot - sent to new members on first join
    // =========================================================================

    /**
     * Sends the complete current state of the group to a newly added member.
     * <p>
     * They have no local state, so we can't send a delta.
     * We send everything: group info, settings, all members.
     */
    public void sendFullSnapshot(Group group, String targetJid) {
        GroupSettings settings = repository.getSettings(group.groupId());
        List<GroupMember> members = repository.listMembers(group.groupId());

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            /*"<message from='%s' to='%s' type='groupchat'>" +*/
            "<snapshot xmlns='%s'>" +
            "<group_id>%s</group_id>" +
            "<jid>%s</jid>" +
            "<name>%s</name>" +
            "<description>%s</description>" +
            "<avatar_url>%s</avatar_url>" +
            "<creator>%s</creator>" +
            "<visibility>%s</visibility>" +
            "<member_count>%d</member_count>" +
            "<version>%d</version>" +
            "<settings>" +
            "<only_admins_can_send>%b</only_admins_can_send>" +
            "<only_admins_can_edit_info>%b</only_admins_can_edit_info>" +
            "<only_admins_can_add>%b</only_admins_can_add>" +
            "<disappearing_seconds>%d</disappearing_seconds>" +
            "<approval_required>%b</approval_required>" +
            "</settings>" +
            "<members>",
            //escapeXml(group.jid()),
            //escapeXml(targetJid),
            GROUP_NS,
            group.groupId(),
            escapeXml(group.jid()),
            escapeXml(group.name()),
            escapeXml(group.description() != null ? group.description() : ""),
            escapeXml(group.avatarUrl() != null ? group.avatarUrl() : ""),
            escapeXml(group.creatorUserId()),
            group.visibility().xmlValue(),
            group.memberCount(),
            group.stateVersion(),
            settings.onlyAdminsCanSend(),
            settings.onlyAdminsCanEditInfo(),
            settings.onlyAdminsCanAdd(),
            settings.disappearingSeconds(),
            settings.approvalRequired()
        ));

        for (GroupMember m : members) {
            xml.append(String.format(
                "<member user_id='%s' jid='%s' phone_number='%s' avatar_url='%s' display_status='%s' nickname='testing nickname' display_name='%s'" +
                " is_admin='%b' is_owner='%b' joined_at='%s'/>",
                escapeXml(m.userId()),
                escapeXml(m.userJid()),
                escapeXml(m.phoneNumber()),
                escapeXml(m.avatarUrl()),
                escapeXml(m.displayStatus()),
                escapeXml(m.displayName() != null ? m.displayName() : ""),
                m.isAdmin(),
                m.isOwner(),
                m.joinedAt()
            ));
        }

        xml.append("</members></snapshot>"/*</message>"*/);

        deliverToMember(targetJid, xml.toString());

        logger.info("Full snapshot sent to " + targetJid
                + " for group " + group.groupId()
                + " (version " + group.stateVersion()
                + ", " + members.size() + " members)");
    }

    // =========================================================================
    // Delivery helpers
    // =========================================================================

    private void broadcastToAllMembers(String groupId, String stanza) {
        List<String> memberJids = repository.listMemberJids(groupId);
        for (String jid : memberJids) {
            deliverToMember(jid, stanza);
        }
    }

    /**
     * Delivers a stanza to a member.
     * If online: writes to their session(s) - covers multi-device.
     * If offline: nothing happens. They'll catch up via delta sync.
     */
    private void deliverToMember(String memberJid, String stanza) {
        notificationPool.execute(() -> {
            try {
                // Send to ALL their active sessions (multi-device) for currently online members
                List<Session> sessions = sessionRegistry
                        .getSessionsByContactId(memberJid);

                for (Session session : sessions) {
                    if (session.isAuthenticated()) {
                        session.writeXML(stanza);
                    }
                }
            } catch (Exception e) {
                logger.warning("Delivery error to " + memberJid
                        + ": " + e.getMessage());
            }
        });
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    public void shutdown() {
        notificationPool.shutdown();
        try {
            if (!notificationPool.awaitTermination(30, TimeUnit.SECONDS)) {
                notificationPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            notificationPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}