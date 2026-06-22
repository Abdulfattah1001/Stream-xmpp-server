package streammessenger.group.service;

import streammessenger.db.DatabaseManager;
import streammessenger.group.model.*;
import streammessenger.group.repository.GroupRepository;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Routes group messages to all members.
 * <p>
 * KEY DIFFERENCE FROM XEP-0045:
 *   - Members receive messages whether or not they're "in the room"
 *     (because there's no room - there's just membership)
 *   - Offline members get messages via offline storage + push notification
 *   - Online members get messages routed to their sessions immediately
 *   - Multi-device: every active session of every member receives the message
 */
public final class GroupMessageRouter {
    private static final Logger logger =
            Logger.getLogger(GroupMessageRouter.class.getName());

    private final GroupRepository repository;
    private final SessionRegistry sessionRegistry;
    private final DatabaseManager db;

    private final ExecutorService fanoutPool = new ThreadPoolExecutor(
            20, 100, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public GroupMessageRouter(GroupRepository repository,
                                SessionRegistry sessionRegistry,
                                DatabaseManager db) {
        this.repository      = repository;
        this.sessionRegistry = sessionRegistry;
        this.db              = db;
    }

    /**
     * Routes a group message.
     * <p>
     * Validation:
     *   1. Sender must be a member
     *   2. Group settings may restrict to admins only
     *   3. Sender must not be muted
     * <p>
     * Delivery:
     *   - For each member (except sender):
     *     - If online: deliver to all their sessions
     *     - If offline: store in offline messages, trigger push
     */
    public void routeMessage(String groupId, String senderUserId,
                              String senderJid, String messageId,
                              String encryptedPayload, String iv,
                              String messageType,
                              String mediaStorageKey,
                              String mimeType,
                              long fileSizeBytes) {
        logger.info("Routing message to group members: sender="+senderJid+" senderJid="+senderJid+" groupId="+groupId+" PAYLOAD: "+encryptedPayload);

        Group group = repository.get(groupId);

        if (group == null) {
            throw new GroupException(GroupException.Code.NOT_FOUND,
                    "Group not found");
        }

        // Verify sender is a member
        GroupMember sender = repository.getMember(groupId, senderUserId);
        if (sender == null) {
            throw new GroupException(GroupException.Code.NOT_MEMBER,
                    "Not a member of this group");
        }

        // Check settings
        GroupSettings settings = repository.getSettings(groupId);
        if (settings.onlyAdminsCanSend() && !sender.canModerate()) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "Only admins can send messages in this group");
        }


        // Check mute
        if (sender.mutedUntil() != null
                && sender.mutedUntil().isAfter(java.time.Instant.now())) {
            throw new GroupException(GroupException.Code.NOT_AUTHORIZED,
                    "You are muted in this group");
        }

        if (messageId == null) messageId = UUID.randomUUID().toString();

        // store the message in the  database for each user

        // Build the stanza
        String stanza = buildMessageStanza(
                group, sender, messageId, encryptedPayload, iv,
                messageType, mediaStorageKey, mimeType, fileSizeBytes
        );

        // Get all member JIDs (except sender)
        List<String> recipientJids = repository.listMemberJids(groupId);

        for (String recipientJid : recipientJids) {
            // Skip the sender of the message
            if (recipientJid.equals(senderJid)) continue;

            String finalMessageId = messageId;
            fanoutPool.execute(() -> {
                deliverToRecipient(recipientJid, stanza,
                        senderJid, finalMessageId,
                        groupId, encryptedPayload, iv,
                        messageType, mediaStorageKey, mimeType,
                        fileSizeBytes);
            });
        }

        logger.fine("Group message routed: groupId=" + groupId
                + " messageId=" + messageId
                + " recipients=" + (recipientJids.size() - 1));
    }

    private void deliverToRecipient(String recipientJid, String stanza,
                                      String senderJid, String messageId,
                                      String groupId, String encryptedPayload,
                                      String iv, String messageType,
                                      String mediaStorageKey, String mimeType,
                                      long fileSizeBytes) {
        try {
            // Deliver to all online sessions for this user (multi-device)
            List<Session> sessions = sessionRegistry
                    .getSessionsByContactId(recipientJid);

            boolean delivered = false;
            for (Session session : sessions) {
                if (session.isAuthenticated()) {
                    if (session.writeXML(stanza)) {
                        delivered = true;
                    }
                }
            }

            if (!delivered) {
                // Offline - store for delivery on next connection
                boolean stored = db.storeGroupEncryptedMessage(
                        groupId, senderJid.split("@")[0],
                        recipientJid.split("@")[0], messageId,
                        messageType, encryptedPayload, iv,
                        mediaStorageKey, null, mimeType,
                        fileSizeBytes, null
                );

                logger.info("STORED: "+stored);
                // TODO: trigger push notification via PushNotificationService
            }
        } catch (Exception e) {
            logger.warning("Delivery error to " + recipientJid
                    + ": " + e.getMessage());
        }
    }

    private String buildEncryptedMessageStanza(Group group, GroupMember sender,
                                        String messageId,
                                        String encryptedPayload, String iv,
                                        String messageType,
                                        String mediaStorageKey,
                                        String mimeType,
                                        long fileSizeBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<message id='%s' from='%s' type='groupchat'>" +
            "<group xmlns='urn:xmpp:group:0' id='%s'/>" +
            "<from_user>%s</from_user>" +
            "<encrypted xmlns='urn:xmpp:e2ee:0'" +
            " msg_type='%s' iv='%s'",
            escapeXml(messageId),
            escapeXml(group.jid()),
            escapeXml(group.groupId()),
            escapeXml(sender.userJid()),
            escapeXml(messageType),
            escapeXml(iv)
        ));

        if (mediaStorageKey != null) {
            sb.append(String.format(" storage_key='%s'",
                    escapeXml(mediaStorageKey)));
        }
        if (mimeType != null) {
            sb.append(String.format(" mime='%s'", escapeXml(mimeType)));
        }
        if (fileSizeBytes > 0) {
            sb.append(String.format(" size='%d'", fileSizeBytes));
        }

        sb.append(">").append(encryptedPayload);
        sb.append("</encrypted></message>");

        return sb.toString();
    }

    private String buildMessageStanza(Group group, GroupMember sender,
                                      String messageId,
                                      String encryptedPayload, String iv,
                                      String messageType,
                                      String mediaStorageKey,
                                      String mimeType,
                                      long fileSizeBytes) {


        return String.format("""
                <message id='%s' from='%s' type='groupchat'>
                <body>%s</body>
                </message>
                """, messageId, group.groupId()+"@conference.omnyrex.com/"+sender.userId(), encryptedPayload);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    public void shutdown() {
        fanoutPool.shutdown();
        try {
            if (!fanoutPool.awaitTermination(30, TimeUnit.SECONDS)) {
                fanoutPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            fanoutPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}