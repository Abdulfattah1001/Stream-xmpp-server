package streammessenger.group.service;

import streammessenger.group.model.*;
import streammessenger.group.repository.GroupRepository;
import streammessenger.session.Session;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles incremental sync of group state.
 * <p>
 * THE FLOW:
 * <p>
 * 1. Client connects after being offline.
 * <p>
 * 2. Client sends a sync request listing groups and their last known versions:
 *      <iq type='get'>
 *        <sync xmlns='urn:xmpp:group:0'>
 *          <group id='g_abc' version='5'/>
 *          <group id='g_def' version='12'/>
 *        </sync>
 *      </iq>
 * <p>
 * 3. Server checks each group:
 *      - If client version == current version → nothing to send
 *      - If client version < current version → send events from
 *        version+1 to current
 *      - If client doesn't know about a group they're in → send full snapshot
 * <p>
 * 4. Server may also discover groups the client doesn't know about
 *    (e.g. they were added while offline).
 */
public final class GroupSyncService {

    private static final Logger logger =
            Logger.getLogger(GroupSyncService.class.getName());

    private static final String GROUP_NS = "urn:xmpp:group:0";
    private static final int MAX_EVENTS_PER_SYNC = 200;

    private final GroupRepository repository;
    private final GroupEventNotifier notifier;

    public GroupSyncService(GroupRepository repository,
                              GroupEventNotifier notifier) {
        this.repository = repository;
        this.notifier   = notifier;
    }

    /**
     * Processes a sync request.
     *
     * @param userId           Requesting user
     * @param userJid          Their JID
     * @param clientKnownState Map of group_id → last known version
     * @param session          For sending responses
     */
    public void processSync(String userId, String userJid,
                              java.util.Map<String, Long> clientKnownState,
                              java.util.Map<String, Long> lastSequence,
                              Session session) {
        // Get all groups the user is currently a member of
        List<Group> userGroups = repository.listUserGroups(userId);

        StringBuilder response = new StringBuilder();
        response.append(String.format(
            "<sync_response xmlns='%s'>", GROUP_NS));

        for (Group group : userGroups) {
            // For each group in the retrieved group list, check the client known version
            Long clientVersion = clientKnownState.get(group.groupId());
            Long lastSeq = lastSequence.get(group.groupId());

            if (clientVersion == null) {
                logger.info("The client doesn't know about this group");
                // Client doesn't know about this group → send snapshot
                notifier.sendFullSnapshot(group, userJid);
                response.append(String.format(
                    "<group id='%s' action='snapshot_sent'/>",
                    group.groupId()
                ));

            } else if (clientVersion < group.stateVersion()) {
                logger.info("The client is behind, sending updates event");
                // Client is behind → send delta events
                List<GroupStateEvent> events = repository.getEventsSinceVersion(
                        group.groupId(), clientVersion, MAX_EVENTS_PER_SYNC);

                response.append(String.format(
                    "<group id='%s' from_version='%d' to_version='%d'" +
                    " event_count='%d'>",
                    group.groupId(),
                    clientVersion,
                    group.stateVersion(),
                    events.size()
                ));

                for (GroupStateEvent event : events) {
                    if(event.eventType().startsWith("member")){
                        GroupMember member = repository.getMember(group.groupId(), event.targetUserId());
                        response.append(buildMemberEventXml(event, member));
                    }else{
                        response.append(buildEventXml(event));
                    }
                }

                response.append("</group>");

                // Update sync version
                repository.updateMemberSyncVersion(
                        group.groupId(), userId, group.stateVersion());

            } else {
                // Client is up-to-date
                response.append(String.format(
                    "<group id='%s' status='up_to_date'/>",
                    group.groupId()
                ));
            }
        }

        // Check for groups the client knows about but isn't in anymore
        for (String knownGroupId : clientKnownState.keySet()) {
            boolean stillMember = userGroups.stream()
                    .anyMatch(g -> g.groupId().equals(knownGroupId));
            if (!stillMember) {
                response.append(String.format(
                    "<group id='%s' status='no_longer_member'/>",
                    knownGroupId
                ));
            }
        }

        response.append("</sync_response>");
        session.writeXML(response.toString());

        logger.fine("Sync processed for " + userId
                + " - " + userGroups.size() + " groups checked");
    }

    public void processClientSync(String userId, String userJid,
                            java.util.Map<String, Long> clientKnownState,
                            java.util.Map<String, Long> lastSequence,
                            Session session) {

        // Get all groups the user is currently a member of
        List<Group> userGroups = repository.listUserGroups(userId);

        StringBuilder response = new StringBuilder();
        response.append(String.format(
                "<sync_response xmlns='%s'>", GROUP_NS));

        for (Group group : userGroups) {
            // For each group in the retrieved group list, check the client known version
            Long clientVersion = clientKnownState.get(group.groupId());
            Long lastSeq = lastSequence.get(group.groupId());

            if (clientVersion == null) {
                logger.info("The client doesn't know about this group");
                // Client doesn't know about this group → send snapshot
                notifier.sendFullSnapshot(group, userJid);
                response.append(String.format(
                        "<group id='%s' action='snapshot_sent'/>",
                        group.groupId()
                ));

            } else if (clientVersion < group.stateVersion()) {
                logger.info("The client is behind, sending updates event");
                // Client is behind → send delta events
                List<GroupStateEvent> events = repository.getEventsSinceVersion(
                        group.groupId(), clientVersion, MAX_EVENTS_PER_SYNC);

                response.append(String.format(
                        "<group id='%s' from_version='%d' to_version='%d'" +
                                " event_count='%d'>",
                        group.groupId(),
                        clientVersion,
                        group.stateVersion(),
                        events.size()
                ));

                for (GroupStateEvent event : events) {
                    if(event.eventType().startsWith("member")){
                        GroupMember member = repository.getMember(group.groupId(), event.targetUserId());
                        response.append(buildMemberEventXml(event, member));
                    }else{
                        response.append(buildEventXml(event));
                    }
                }

                response.append("</group>");

                // Update sync version
                repository.updateMemberSyncVersion(
                        group.groupId(), userId, group.stateVersion());

            } else {
                // Client is up-to-date
                response.append(String.format(
                        "<group id='%s' status='up_to_date'/>",
                        group.groupId()
                ));
            }
        }

        // Check for groups the client knows about but isn't in anymore
        for (String knownGroupId : clientKnownState.keySet()) {
            boolean stillMember = userGroups.stream()
                    .anyMatch(g -> g.groupId().equals(knownGroupId));
            if (!stillMember) {
                response.append(String.format(
                        "<group id='%s' status='no_longer_member'/>",
                        knownGroupId
                ));
            }
        }

        response.append("</sync_response>");
        session.writeXML(response.toString());

        logger.fine("Sync processed for " + userId
                + " - " + userGroups.size() + " groups checked");
    }

    private String buildMemberEventXml(GroupStateEvent event, GroupMember member) {
        return String.format(
                "<event id='%s' version='%d' type='%s' actor='%s'" +
                        " target='%s' created_at='%s'>" +
                        "<member jid='%s' user_id='%s' display_name='%s' display_status='%s' phone_number='%s'" +
                        " is_admin='false' nickname='%s' joined_at='%s'/>" +
                        "</event>",
                event.eventId(),
                event.stateVersion(),
                event.eventType(),
                escapeXml(event.actorUserId() != null ? event.actorUserId() : ""),
                escapeXml(event.targetUserId() != null ? event.targetUserId() : ""),
                event.createdAt(),
                member.userJid(),
                member.userId(),
                member.displayName(),
                member.displayStatus(),
                member.phoneNumber(),
                member.displayName(),
                member.joinedAt().toString()
        );
    }

    private String buildMemberMessageXml(String groupJid, String senderId, String receiverId, String body){
        return String.format(
                "<message id='%s' type='groupchat' from='%s' to='%s'>" +
                "<body>%s</body>" +
                        "</message>", UUID.randomUUID().toString(), groupJid+"/"+senderId, receiverId, body);
    }
    private String buildEventXml(GroupStateEvent event) {
        return String.format(
            "<event id='%s' version='%d' type='%s' actor='%s'" +
            " target='%s' created_at='%s'>%s</event>",
            event.eventId(),
            event.stateVersion(),
            event.eventType(),
            escapeXml(event.actorUserId() != null ? event.actorUserId() : ""),
            escapeXml(event.targetUserId() != null ? event.targetUserId() : ""),
            event.createdAt(),
            event.payloadJson() // Already JSON, embed as-is
        );
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}