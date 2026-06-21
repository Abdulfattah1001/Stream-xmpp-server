package streammessenger.muc.service;


import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.muc.model.Affiliation;
import streammessenger.muc.model.GroupEventType;
import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.model.GroupSystemEvent;
import streammessenger.muc.model.Occupant;
import streammessenger.muc.repository.GroupRepository;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Distributes group messages to all online occupants.
 * <p>
 * Two key optimizations:
 * <p>
 * 1. PARALLEL DELIVERY:
 *    Don't deliver sequentially - one slow socket would block everyone.
 *    Use a dedicated thread pool to fan out in parallel.
 * <p>
 * 2. ONLINE-FIRST FILTERING:
 *    Iterate the room's in-memory occupants (already filtered to online).
 *    Don't query DB for membership on every message.
 * <p>
 * For offline members: a separate background job stores in
 * group_message_history for later retrieval (caught up on next presence join).
 */
public final class FanoutService {

    private static final Logger logger =
            Logger.getLogger(FanoutService.class.getName());

    private final SessionRegistry sessionRegistry;

    private final DatabaseManager databaseManager;
    /**
     * Bounded parallel executor.
     * 20 threads handles 1M+ deliveries/sec across many rooms.
     * Larger rooms benefit from parallel delivery within a room.
     */
    private final ExecutorService fanoutExecutor = new ThreadPoolExecutor(
            20, 100, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public FanoutService(SessionRegistry sessionRegistry, DatabaseManager db) {
        this.sessionRegistry = sessionRegistry;
        this.databaseManager = db;
    }

    /**
     * Delivers a message to all online occupants except the sender.
     *
     * @param room        The group room
     * @param senderUserId User to skip (the sender)
     * @param stanzaXml   The complete <message> stanza ready to send
     */
    public void fanoutMessage(GroupRoom room, String senderUserId,
                              String stanzaXml) {

        for (Occupant occupant : room.getOccupants()) {
            if (occupant.userId().equals(senderUserId)) continue;
            if (!occupant.role().canSpeak()) continue;

            // Submit each delivery to the thread pool
            fanoutExecutor.execute(() -> {
                try {
                    sessionRegistry.getByUid(occupant.sessionUid())
                            .filter(Session::isAuthenticated)
                            .ifPresent(s -> s.writeXML(stanzaXml));
                } catch (Exception e) {
                    logger.warning("Fanout error to "
                            + occupant.userJid() + ": " + e.getMessage());
                }
            });
        }
    }


    public void broadcastMemberAdded(GroupRoom room, String newMemberUserId,
                                      String newMemberJid,
                                      Affiliation affiliation) {
        String stanza = String.format(
            "<message from='%s' type='groupchat'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<item affiliation='%s' jid='%s'/>" +
            "<status code='100'/>" +
            "</x></message>",
            escapeXml(room.getJid()),
            affiliation.xmlValue(),
            escapeXml(newMemberJid)
        );

        fanoutToAll(room, stanza);
    }

    public void broadcastMemberAddedToGroup(GroupRoom room, String newMemberUserId,
                                            String newMemberJid,
                                            List<GroupRepository.MemberRecord> memberRecordList) {
        if(memberRecordList.isEmpty()) return;
        GroupSystemEvent systemEvent = new GroupSystemEvent(GroupEventType.MEMBER_JOINED_VIA_LINK, newMemberUserId, newMemberUserId, Instant.now());
        String from = room.getJid()+"/"+newMemberUserId;
        StringBuilder members = new StringBuilder();
        members.append("<membership id='" + UUID.randomUUID().toString() + "' group_id='" + room.getGroupId() + "'>"); // A unidirectional stanza, only server can sends <membership>
        for(GroupRepository.MemberRecord record: memberRecordList){
            members.append("<member display_name='").append(record.nickname())
                    .append("' avatar_url='").append(record.avatar_url())
                    .append("' phone_number='").append(record.phone_number())
                    .append("' display_status='").append(record.status())
                    .append("' affiliation='").append(record.affiliation().name())
                    .append("' uid='").append(record.userId())
                    .append("' jid='").append(record.userJid()).append("'/>");
        }
        members.append("</membership>");
        logger.info("Member number to sent is: "+members);
        /*sessionRegistry.getByContactId(newMemberUserId)
                .filter(Session::isAuthenticated)
                .ifPresentOrElse(s -> s.writeXML(members.toString()), () -> {
                    //TODO: Cache it till the user can receive it
                    logger.info("Can't send the members list at the moment");
                });*/
        for(GroupRepository.MemberRecord record : memberRecordList){
            String text = newMemberUserId + " joined";
            String id = UUID.randomUUID().toString();
            String stanza = String.format("<message id='%s' from='%s' type='groupchat'>" +
                    "<body>%s</body>" +
                    "<system xmlns='urn:xmpp:group:0'>" +
                        "<event type='" + systemEvent.type().name() + "' actor='" + systemEvent.actorId() + "' subject='" + systemEvent.subjectId() + "' />"+
                    "</system>" +
                    "</message>", id, from, text);
            sessionRegistry.getByContactId(record.userId())
                    .filter(Session::isAuthenticated)
                    .ifPresentOrElse(s -> s.writeXML(stanza), () -> databaseManager.storeSystemEvent(id, from, systemEvent.type().name(), systemEvent.actorId(), systemEvent.subjectId(), record.userId()));
        }
    }

    public void broadcastMemberRemoved(GroupRoom room, String removedUserId,
                                        String removedJid,
                                        String actorUserId, String reason) {
        String stanza = String.format(
            "<message from='%s' type='groupchat'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<item affiliation='none' jid='%s'>" +
            "<actor jid='%s'/>" +
            "<reason>%s</reason>" +
            "</item>" +
            "<status code='321'/>" +  // removed because affiliation changed
            "</x></message>",
            escapeXml(room.getJid()),
            escapeXml(removedJid),
            escapeXml(actorUserId),
            escapeXml(reason != null ? reason : "")
        );

        fanoutToAll(room, stanza);
    }

    public void broadcastAffiliationChange(GroupRoom room, String userId,
                                            String userJid,
                                            Affiliation newAffiliation) {
        String stanza = String.format(
            "<message from='%s' type='groupchat'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<item affiliation='%s' jid='%s'/>" +
            "</x></message>",
            escapeXml(room.getJid()),
            newAffiliation.xmlValue(),
            escapeXml(userJid)
        );

        fanoutToAll(room, stanza);
    }

    public void broadcastOwnershipTransfer(GroupRoom room,
                                            String oldOwnerUserId,
                                            String newOwnerUserId) {
        // Two affiliation changes broadcast as one notification
        String stanza = String.format(
            "<message from='%s' type='groupchat'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<item affiliation='admin' jid='%s'/>" +
            "<item affiliation='owner' jid='%s'/>" +
            "<status code='110'/>" +
            "</x></message>",
            escapeXml(room.getJid()),
            escapeXml(oldOwnerUserId),
            escapeXml(newOwnerUserId)
        );

        fanoutToAll(room, stanza);
    }

    public void broadcastConfigChange(GroupRoom room) {
        // XEP-0045 §10.9 - status code 104 = config change
        String stanza = String.format(
            "<message from='%s' type='groupchat'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<status code='104'/>" +
            "</x></message>",
            escapeXml(room.getJid())
        );

        fanoutToAll(room, stanza);
    }

    public void broadcastDestruction(GroupRoom room, String reason) {
        String stanza = String.format(
            "<presence from='%s' type='unavailable'>" +
            "<x xmlns='http://jabber.org/protocol/muc#user'>" +
            "<item affiliation='none' role='none'/>" +
            "<destroy>" +
            "<reason>%s</reason>" +
            "</destroy>" +
            "</x></presence>",
            escapeXml(room.getJid()),
            escapeXml(reason != null ? reason : "")
        );

        fanoutToAll(room, stanza);
    }

    private void fanoutToAll(GroupRoom room, String stanza) {
        for (Occupant occupant : room.getOccupants()) {
            fanoutExecutor.execute(() -> {
                try {
                    sessionRegistry.getByUid(occupant.sessionUid())
                            .filter(Session::isAuthenticated)
                            .ifPresent(s -> s.writeXML(stanza));
                } catch (Exception e) {
                    logger.warning("Fanout error: " + e.getMessage());
                }
            });
        }
    }

    private void fanOutToAllMembers(GroupRoom room, String stanza) {
        logger.info("Fanning out new member to group members");
        //TODO: Gets the group members to send a system message [joined,removed,banned e.t.c]

        for (Occupant occupant : room.getOccupants()) {
            fanoutExecutor.execute(() -> {
                try {
                    sessionRegistry.getByUid(occupant.sessionUid())
                            .filter(Session::isAuthenticated)
                            .ifPresent(s -> s.writeXML(stanza));
                } catch (Exception e) {
                    logger.warning("Fanout error: " + e.getMessage());
                }
            });
        }
    }

    public void shutdown() {
        fanoutExecutor.shutdown();
        try {
            if (!fanoutExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                fanoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            fanoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}
