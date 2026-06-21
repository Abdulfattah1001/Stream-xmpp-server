package streammessenger.group.handler;

import com.xmpp.group.model.*;
import com.xmpp.group.repository.GroupRepository;
import com.xmpp.group.service.*;
import com.xmpp.session.Session;
import com.xmpp.stream.stanza.StanzaHandler;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.*;
import java.util.logging.Logger;

/**
 * Routes group-related XMPP stanzas.
 *
 * Custom namespace: urn:xmpp:group:0
 *
 * Two element types:
 *
 * 1. <message type='group_chat'> - sending a message to a group
 *    <message id='m1' to='g_abc@conference.domain' type='group_chat'>
 *      <group xmlns='urn:xmpp:group:0' id='g_abc'/>
 *      <encrypted xmlns='urn:xmpp:e2ee:0' ...>BASE64</encrypted>
 *    </message>
 *
 * 2. <iq type='get|set'> with group operation
 *    <iq type='set' id='q1'>
 *      <group xmlns='urn:xmpp:group:0' action='create'>
 *        <name>My Group</name>
 *      </group>
 *    </iq>
 *
 * Supported actions:
 *   create                 → Create new group
 *   add_member             → Admin adds someone
 *   remove_member          → Admin removes someone
 *   leave                  → Voluntarily leave
 *   grant_admin            → Promote member to admin
 *   revoke_admin           → Demote admin to member
 *   transfer_ownership     → Transfer owner role
 *   update_metadata        → Change name/description/avatar
 *   update_settings        → Change settings
 *   create_link            → Generate invite link
 *   revoke_link            → Revoke invite link
 *   join_via_link          → Join using a link (no actor)
 *   list_links             → List all active links
 *   list_members           → Get full member list
 *   sync                   → Delta sync for groups user is in
 */
public final class GroupStanzaHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(GroupStanzaHandler.class.getName());

    private static final String GROUP_NS = "urn:xmpp:group:0";

    private final GroupRepository repository;
    private final GroupService groupService;
    private final GroupMessageRouter messageRouter;
    private final GroupSyncService syncService;
    private final String groupDomain;

    public GroupStanzaHandler(GroupRepository repository,
                                GroupService groupService,
                                GroupMessageRouter messageRouter,
                                GroupSyncService syncService,
                                String groupDomain) {
        this.repository    = repository;
        this.groupService  = groupService;
        this.messageRouter = messageRouter;
        this.syncService   = syncService;
        this.groupDomain   = groupDomain;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader,
                        Session session) {
        if (!session.isAuthenticated()) {
            consumeElement(reader);
            return;
        }

        String localName = element.getName().getLocalPart();

        switch (localName) {
            case "message" -> handleGroupMessage(element, reader, session);
            case "iq"      -> handleGroupIQ(element, reader, session);
            default        -> consumeElement(reader);
        }
    }

    // =========================================================================
    // Group message
    // =========================================================================

    /**
     * Routes a group message to all members.
     */
    private void handleGroupMessage(StartElement element,
                                      XMLEventReader reader,
                                      Session session) {
        String to        = getAttr(element, "to");
        String type      = getAttr(element, "type");
        String messageId = getAttr(element, "id");

        if (!"group_chat".equals(type) || to == null) {
            consumeElement(reader);
            return;
        }

        String groupJid = bareJid(to);
        String groupId  = groupJid.substring(0, groupJid.indexOf('@'));
        String userId   = extractUserId(session.getContactId());

        ParsedGroupMessage parsed = parseGroupMessage(reader);

        try {
            messageRouter.routeMessage(
                    groupId,
                    userId,
                    session.getContactId(),
                    messageId,
                    parsed.encryptedPayload(),
                    parsed.iv(),
                    parsed.messageType() != null
                            ? parsed.messageType() : "text",
                    parsed.mediaStorageKey(),
                    parsed.mimeType(),
                    parsed.fileSizeBytes()
            );

            // Echo confirmation to sender
            session.writeXML(String.format(
                "<message id='%s' from='%s' to='%s' type='receipt'>" +
                "<received xmlns='urn:xmpp:receipts' id='%s'/>" +
                "</message>",
                UUID.randomUUID(),
                escapeXml(groupJid),
                escapeXml(session.getContactId()),
                escapeXml(messageId != null ? messageId : "")
            ));

        } catch (GroupException e) {
            sendMessageError(session, messageId, e.getCode().xmppCondition,
                    e.getMessage());
        }
    }

    // =========================================================================
    // Group IQ
    // =========================================================================

    private void handleGroupIQ(StartElement element, XMLEventReader reader,
                                Session session) {
        String iqId = getAttr(element, "id");

        ParsedGroupIQ parsed = parseGroupIQ(reader);
        if (parsed == null || parsed.action() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        String userId = extractUserId(session.getContactId());

        try {
            switch (parsed.action()) {
                case "create"             -> handleCreate(parsed, iqId, userId, session);
                case "add_member"         -> handleAddMember(parsed, iqId, userId, session);
                case "remove_member"      -> handleRemoveMember(parsed, iqId, userId, session);
                case "leave"              -> handleLeave(parsed, iqId, userId, session);
                case "grant_admin"        -> handleGrantAdmin(parsed, iqId, userId, session);
                case "revoke_admin"       -> handleRevokeAdmin(parsed, iqId, userId, session);
                case "transfer_ownership" -> handleTransferOwnership(parsed, iqId, userId, session);
                case "update_metadata"    -> handleUpdateMetadata(parsed, iqId, userId, session);
                case "update_settings"    -> handleUpdateSettings(parsed, iqId, userId, session);
                case "create_link"        -> handleCreateLink(parsed, iqId, userId, session);
                case "revoke_link"        -> handleRevokeLink(parsed, iqId, userId, session);
                case "join_via_link"      -> handleJoinViaLink(parsed, iqId, userId, session);
                case "list_links"         -> handleListLinks(parsed, iqId, userId, session);
                case "list_members"       -> handleListMembers(parsed, iqId, userId, session);
                case "list_groups"        -> handleListGroups(iqId, userId, session);
                case "sync"               -> handleSync(parsed, iqId, userId, session);
                default                   -> sendIQError(session, iqId,
                                                "feature-not-implemented");
            }
        } catch (GroupException e) {
            sendIQErrorWithText(session, iqId, e.getCode().xmppCondition,
                    e.getMessage());
        } catch (Exception e) {
            logger.severe("Group IQ error: " + e.getMessage());
            sendIQError(session, iqId, "internal-server-error");
        }
    }

    // =========================================================================
    // Action handlers
    // =========================================================================

    private void handleCreate(ParsedGroupIQ p, String iqId,
                                String userId, Session session) {
        Group group = groupService.createGroup(
                p.name(),
                p.description(),
                userId,
                session.getContactId(),
                p.visibility() != null
                        ? GroupVisibility.fromString(p.visibility())
                        : GroupVisibility.PRIVATE,
                p.maxMembers() > 0 ? p.maxMembers() : 256
        );

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='created'>" +
            "<group_id>%s</group_id>" +
            "<jid>%s</jid>" +
            "<name>%s</name>" +
            "<version>%d</version>" +
            "</group></iq>",
            escapeXml(iqId), GROUP_NS,
            group.groupId(),
            escapeXml(group.jid()),
            escapeXml(group.name()),
            group.stateVersion()
        ));
    }

    private void handleAddMember(ParsedGroupIQ p, String iqId,
                                   String userId, Session session) {
        if (p.groupId() == null || p.targetUserId() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        groupService.addMember(p.groupId(), userId,
                p.targetUserId(), p.targetUserJid());

        session.writeXML(buildSuccessResult(iqId, "member_added"));
    }

    private void handleRemoveMember(ParsedGroupIQ p, String iqId,
                                      String userId, Session session) {
        if (p.groupId() == null || p.targetUserId() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        groupService.removeMember(p.groupId(), userId, p.targetUserId());
        session.writeXML(buildSuccessResult(iqId, "member_removed"));
    }

    private void handleLeave(ParsedGroupIQ p, String iqId,
                              String userId, Session session) {
        if (p.groupId() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        // Self-leave: target is the user themselves
        groupService.removeMember(p.groupId(), userId, userId);
        session.writeXML(buildSuccessResult(iqId, "left"));
    }

    private void handleGrantAdmin(ParsedGroupIQ p, String iqId,
                                    String userId, Session session) {
        groupService.grantAdmin(p.groupId(), userId, p.targetUserId());
        session.writeXML(buildSuccessResult(iqId, "admin_granted"));
    }

    private void handleRevokeAdmin(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        groupService.revokeAdmin(p.groupId(), userId, p.targetUserId());
        session.writeXML(buildSuccessResult(iqId, "admin_revoked"));
    }

    private void handleTransferOwnership(ParsedGroupIQ p, String iqId,
                                           String userId, Session session) {
        groupService.transferOwnership(p.groupId(), userId, p.targetUserId());
        session.writeXML(buildSuccessResult(iqId, "ownership_transferred"));
    }

    private void handleUpdateMetadata(ParsedGroupIQ p, String iqId,
                                        String userId, Session session) {
        groupService.updateMetadata(p.groupId(), userId,
                p.name(), p.description(), p.avatarUrl());
        session.writeXML(buildSuccessResult(iqId, "metadata_updated"));
    }

    private void handleUpdateSettings(ParsedGroupIQ p, String iqId,
                                        String userId, Session session) {
        GroupSettings settings = new GroupSettings(
                p.onlyAdminsCanSend(),
                p.onlyAdminsCanEditInfo(),
                p.onlyAdminsCanAdd(),
                p.disappearingSeconds(),
                p.approvalRequired()
        );

        groupService.updateSettings(p.groupId(), userId, settings);
        session.writeXML(buildSuccessResult(iqId, "settings_updated"));
    }

    private void handleCreateLink(ParsedGroupIQ p, String iqId,
                                    String userId, Session session) {
        GroupRepository.InviteLink link =
                groupService.createInviteLink(p.groupId(), userId);

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='link_created'>" +
            "<token>%s</token>" +
            "<created_by>%s</created_by>" +
            "<created_at>%s</created_at>" +
            "</group></iq>",
            escapeXml(iqId), GROUP_NS,
            link.token(),
            escapeXml(link.createdByUserId()),
            link.createdAt()
        ));
    }

    private void handleRevokeLink(ParsedGroupIQ p, String iqId,
                                    String userId, Session session) {
        if (p.linkToken() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        groupService.revokeInviteLink(p.groupId(), userId, p.linkToken());
        session.writeXML(buildSuccessResult(iqId, "link_revoked"));
    }

    /**
     * Join via invite link.
     *
     * KEY POINT: No "actor" here - the user is joining themselves.
     * No admin involvement required.
     * The user clicks a link → they're added.
     */
    private void handleJoinViaLink(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        if (p.linkToken() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        String groupId = groupService.joinViaLink(
                p.linkToken(), userId, session.getContactId());

        // Client gets confirmation with the group_id
        // The full snapshot (with members list) was sent separately
        // by the notifier as a <message><snapshot>
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='joined_via_link'>" +
            "<group_id>%s</group_id>" +
            "<note>Snapshot stanza follows with full group state</note>" +
            "</group></iq>",
            escapeXml(iqId), GROUP_NS,
            groupId
        ));
    }

    private void handleListLinks(ParsedGroupIQ p, String iqId,
                                   String userId, Session session) {
        GroupMember member = repository.getMember(p.groupId(), userId);
        if (member == null || !member.canModerate()) {
            sendIQError(session, iqId, "forbidden");
            return;
        }

        List<GroupRepository.InviteLink> links =
                repository.listInviteLinks(p.groupId());

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='links'>",
            escapeXml(iqId), GROUP_NS
        ));

        for (GroupRepository.InviteLink link : links) {
            xml.append(String.format(
                "<link token='%s' created_by='%s' created_at='%s'/>",
                link.token(),
                escapeXml(link.createdByUserId()),
                link.createdAt()
            ));
        }

        xml.append("</group></iq>");
        session.writeXML(xml.toString());
    }

    /**
     * Returns the full member list of a group.
     * Useful when client needs to refresh the entire list
     * (e.g. after detecting state drift).
     *
     * Normal flow: members list is delivered via the snapshot
     * stanza when joining, then maintained incrementally via events.
     */
    private void handleListMembers(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        if (p.groupId() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        // Authorization: must be a member to see member list
        if (!repository.isMember(p.groupId(), userId)) {
            sendIQError(session, iqId, "forbidden");
            return;
        }

        Group group = repository.get(p.groupId());
        List<GroupMember> members = repository.listMembers(p.groupId());

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='members'>" +
            "<group_id>%s</group_id>" +
            "<version>%d</version>" +
            "<count>%d</count>",
            escapeXml(iqId), GROUP_NS,
            group.groupId(),
            group.stateVersion(),
            members.size()
        ));

        for (GroupMember m : members) {
            xml.append(String.format(
                "<member user_id='%s' jid='%s' display_name='%s'" +
                " is_admin='%b' is_owner='%b' joined_at='%s'/>",
                escapeXml(m.userId()),
                escapeXml(m.userJid()),
                escapeXml(m.displayName() != null ? m.displayName() : ""),
                m.isAdmin(),
                m.isOwner(),
                m.joinedAt()
            ));
        }

        xml.append("</group></iq>");
        session.writeXML(xml.toString());
    }

    /**
     * Lists all groups the user is a member of.
     * Used by the client at startup or to refresh group list.
     *
     * Returns lightweight info (id, name, version, last_synced).
     * Client uses this to know which groups to sync.
     */
    private void handleListGroups(String iqId, String userId,
                                    Session session) {
        List<Group> groups = repository.listUserGroups(userId);

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='groups'>",
            escapeXml(iqId), GROUP_NS
        ));

        for (Group g : groups) {
            xml.append(String.format(
                "<group group_id='%s' jid='%s' name='%s'" +
                " member_count='%d' version='%d'/>",
                g.groupId(),
                escapeXml(g.jid()),
                escapeXml(g.name()),
                g.memberCount(),
                g.stateVersion()
            ));
        }

        xml.append("</group></iq>");
        session.writeXML(xml.toString());
    }

    /**
     * Delta sync handler.
     *
     * Client sends what it knows; server sends what's new.
     *
     * Request:
     *   <iq type='get' id='s1'>
     *     <group xmlns='urn:xmpp:group:0' action='sync'>
     *       <known group_id='g_abc' version='5'/>
     *       <known group_id='g_def' version='12'/>
     *     </group>
     *   </iq>
     *
     * Response: handled by GroupSyncService which sends:
     *   - For each up-to-date group: status='up_to_date'
     *   - For each behind group: delta events
     *   - For each unknown group (user was added while offline):
     *     full snapshot via separate <message><snapshot>
     *   - For each removed group: status='no_longer_member'
     */
    private void handleSync(ParsedGroupIQ p, String iqId,
                              String userId, Session session) {
        // Acknowledge the sync request first
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='sync_started'/>" +
            "</iq>",
            escapeXml(iqId), GROUP_NS
        ));

        // Then process the sync (sends responses as separate stanzas)
        syncService.processSync(userId, session.getContactId(),
                p.knownVersions(), session);
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private ParsedGroupMessage parseGroupMessage(XMLEventReader reader) {
        String encryptedPayload = null;
        String iv = null;
        String messageType = "text";
        String mediaStorageKey = null;
        String mimeType = null;
        long fileSizeBytes = 0;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns = se.getName().getNamespaceURI();

                    if ("encrypted".equals(name)
                            && "urn:xmpp:e2ee:0".equals(ns)) {
                        iv = getAttr(se, "iv");
                        messageType = getAttr(se, "msg_type");
                        mediaStorageKey = getAttr(se, "storage_key");
                        mimeType = getAttr(se, "mime");
                        String size = getAttr(se, "size");
                        if (size != null) {
                            try { fileSizeBytes = Long.parseLong(size); }
                            catch (NumberFormatException ignored) {}
                        }
                        encryptedPayload = readText(reader);
                        depth--;
                    }
                }
                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("parseGroupMessage: " + e.getMessage());
        }

        return new ParsedGroupMessage(encryptedPayload, iv, messageType,
                mediaStorageKey, mimeType, fileSizeBytes);
    }

    private ParsedGroupIQ parseGroupIQ(XMLEventReader reader) {
        String action = null, groupId = null;
        String targetUserId = null, targetUserJid = null;
        String name = null, description = null, avatarUrl = null;
        String linkToken = null, visibility = null;
        int maxMembers = 0, disappearingSeconds = 0;
        boolean onlyAdminsSend = false, onlyAdminsEdit = true;
        boolean onlyAdminsAdd = false, approvalRequired = false;
        Map<String, Long> knownVersions = new HashMap<>();

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String elName = se.getName().getLocalPart();
                    String ns = se.getName().getNamespaceURI();

                    if ("group".equals(elName) && GROUP_NS.equals(ns)) {
                        action = getAttr(se, "action");
                    }

                    if ("known".equals(elName)) {
                        // For sync action
                        String gid = getAttr(se, "group_id");
                        String ver = getAttr(se, "version");
                        if (gid != null && ver != null) {
                            try {
                                knownVersions.put(gid, Long.parseLong(ver));
                            } catch (NumberFormatException ignored) {}
                        }
                    }

                    switch (elName) {
                        case "group_id"            -> groupId = readText(reader);
                        case "target_user_id"      -> targetUserId = readText(reader);
                        case "target_user_jid"     -> targetUserJid = readText(reader);
                        case "name"                -> name = readText(reader);
                        case "description"         -> description = readText(reader);
                        case "avatar_url"          -> avatarUrl = readText(reader);
                        case "token"               -> linkToken = readText(reader);
                        case "visibility"          -> visibility = readText(reader);
                        case "max_members"         -> maxMembers = parseIntSafe(readText(reader));
                        case "disappearing_seconds" -> disappearingSeconds = parseIntSafe(readText(reader));
                        case "only_admins_can_send" -> onlyAdminsSend = parseBoolSafe(readText(reader));
                        case "only_admins_can_edit_info" -> onlyAdminsEdit = parseBoolSafe(readText(reader));
                        case "only_admins_can_add" -> onlyAdminsAdd = parseBoolSafe(readText(reader));
                        case "approval_required"   -> approvalRequired = parseBoolSafe(readText(reader));
                    }
                    depth--;
                }
                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("parseGroupIQ: " + e.getMessage());
            return null;
        }

        return new ParsedGroupIQ(
                action, groupId, targetUserId, targetUserJid,
                name, description, avatarUrl, linkToken, visibility,
                maxMembers, disappearingSeconds,
                onlyAdminsSend, onlyAdminsEdit, onlyAdminsAdd,
                approvalRequired,
                knownVersions
        );
    }

    // =========================================================================
    // Response builders
    // =========================================================================

    private String buildSuccessResult(String iqId, String action) {
        return String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='%s'/>" +
            "</iq>",
            escapeXml(iqId), GROUP_NS, action
        );
    }

    private void sendIQError(Session session, String iqId, String condition) {
        session.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='cancel'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            condition
        ));
    }

    private void sendIQErrorWithText(Session session, String iqId,
                                       String condition, String text) {
        session.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='cancel'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "<text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>%s</text>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            condition,
            escapeXml(text)
        ));
    }

    private void sendMessageError(Session session, String messageId,
                                    String condition, String text) {
        session.writeXML(String.format(
            "<message id='%s' type='error'>" +
            "<error type='auth'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "<text>%s</text>" +
            "</error></message>",
            escapeXml(messageId != null ? messageId : ""),
            condition,
            escapeXml(text)
        ));
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private String readText(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent e = reader.peek();
                if (e.isCharacters()) {
                    reader.nextEvent();
                    sb.append(e.asCharacters().getData());
                } else break;
            }
            if (reader.hasNext() && reader.peek().isEndElement()) {
                reader.nextEvent();
            }
        } catch (XMLStreamException ignored) {}
        return sb.toString().trim();
    }

    private String getAttr(StartElement el, String name) {
        Attribute attr = el.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private void consumeElement(XMLEventReader reader) {
        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent e = reader.nextEvent();
                if (e.isStartElement()) depth++;
                if (e.isEndElement()) depth--;
            }
        } catch (XMLStreamException ignored) {}
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private boolean parseBoolSafe(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String bareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Records
    // =========================================================================

    private record ParsedGroupMessage(
            String encryptedPayload,
            String iv,
            String messageType,
            String mediaStorageKey,
            String mimeType,
            long fileSizeBytes
    ) {}

    private record ParsedGroupIQ(
            String action,
            String groupId,
            String targetUserId,
            String targetUserJid,
            String name,
            String description,
            String avatarUrl,
            String linkToken,
            String visibility,
            int maxMembers,
            int disappearingSeconds,
            boolean onlyAdminsCanSend,
            boolean onlyAdminsCanEditInfo,
            boolean onlyAdminsCanAdd,
            boolean approvalRequired,
            Map<String, Long> knownVersions
    ) {}
}