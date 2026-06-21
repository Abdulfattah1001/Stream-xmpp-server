package streammessenger.muc.handler;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.muc.exceptions.MucException;
import streammessenger.muc.model.Affiliation;
import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.model.GroupSettings;
import streammessenger.muc.model.GroupVisibility;
import streammessenger.muc.model.Occupant;
import streammessenger.muc.model.Role;
import streammessenger.muc.repository.GroupRepository;
import streammessenger.muc.service.FanoutService;
import streammessenger.muc.service.GroupRegistry;
import streammessenger.muc.service.GroupService;
import streammessenger.muc.service.InvitationService;
import streammessenger.muc.service.MembershipService;
import streammessenger.muc.service.PresenceBroadcaster;
import streammessenger.session.Session;
import streammessenger.stanza.StanzaHandler;

/**
 * Routes group-related XMPP stanzas to the appropriate service.
 *
 * Stanza types handled:
 *
 *   <presence to='groupjid/nick'>           → join room
 *   <presence to='groupjid/nick' type='unavailable'> → leave
 *   <message to='groupjid' type='groupchat'> → group message
 *   <iq xmlns='urn:xmpp:group:0'>           → group ops (custom)
 *
 * Detection: any stanza whose 'to' attribute matches a group JID
 * is routed here from the main XMPPStreamProcessor.
 */
public final class GroupStanzaHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(GroupStanzaHandler.class.getName());

    private static final String GROUP_NS = "urn:xmpp:group:0";
    private static final String MUC_NS   = "http://jabber.org/protocol/muc";
    private static final String MUC_USER_NS = MUC_NS + "#user";

    private final GroupRepository repository;
    private final GroupRegistry registry;
    private final GroupService groupService;
    private final MembershipService membershipService;
    private final InvitationService invitationService;
    private final FanoutService fanoutService;
    private final PresenceBroadcaster presenceBroadcaster;
    private final DatabaseManager db;
    private final String mucDomain;

    public GroupStanzaHandler(GroupRepository repository,
                               GroupRegistry registry,
                               GroupService groupService,
                               MembershipService membershipService,
                               InvitationService invitationService,
                               FanoutService fanoutService,
                               PresenceBroadcaster presenceBroadcaster,
                               DatabaseManager db,
                               String mucDomain) {
        this.repository          = repository;
        this.registry            = registry;
        this.groupService        = groupService;
        this.membershipService   = membershipService;
        this.invitationService   = invitationService;
        this.fanoutService       = fanoutService;
        this.presenceBroadcaster = presenceBroadcaster;
        this.db                  = db;
        this.mucDomain           = mucDomain;
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
            case "presence" -> handlePresence(element, reader, session);
            case "message"  -> handleMessage(element, reader, session);
            case "iq"       -> handleIQ(element, reader, session);
            default -> consumeElement(reader);
        }
    }

    public record GroupInviteInfo(
            GroupRepository.GroupRecord groupRecord,
            GroupSettings groupSettings,
            JoinLinkInfo linkInfo,
            DatabaseManager.UserRecord creator,
            boolean isMember,
            boolean isBanned
    ) {}

    public record JoinLinkInfo(
            boolean revoked,
            Instant expiresAt,
            boolean oneTime,
            boolean used,
            int useCount,
            int maxUses
    ) {}

    private void handleLinkPreview1(ParsedGroupIQ p, String iqId, String userId, Session session){
        // 1: Validate the token for null
        if (p.linkToken() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        // 2: Validate the token for validity
        GroupInviteInfo info = repository.groupInviteInfo(userId, p.linkToken());

        if (info == null) {
            sendIQError(session, iqId, "item-not-found");
            return;
        }

        GroupSettings settings = info.groupSettings();
        GroupRepository.GroupRecord room = info.groupRecord();
        DatabaseManager.UserRecord creator = info.creator();


        String response = String.format(
                "<iq type='result' id='%s'>" +
                        "<group xmlns='%s' action='created'>" +
                        "<group_id>%s</group_id>" +
                        "<jid>%s</jid>" +
                        "<name>%s</name>" +
                        "<description>%s</description>" +
                        "<visibility>%s</visibility>" +
                        "<max_members>%d</max_members>" +
                        "<member_count>%d</member_count>" +
                        "<created_at>%s</created_at>" +
                        "<creator_jid>%s</creator_jid>" +
                        //Creator information
                        "<creator jid='%s' display_name='%s' avatar_url='%s'/>" +
                        //Settings
                        "<settings>" +
                        "<only_admins_send>%b</only_admins_send>" +
                        "<only_admins_meta>%b</only_admins_meta>" +
                        "<only_admins_add>%b</only_admins_add>" +
                        "<membership_approval>%b</membership_approval>" +
                        "<announcement_mode>%b</announcement_mode>" +
                        "<allow_history>%b</allow_history>" +
                        "<history_max_messages>%d</history_max_messages>" +
                        "<disappearing_seconds>%d</disappearing_seconds>" +
                        "</settings>" +
                        "<is_member>%b</is_member>" +
                        "<is_banned>%b</is_banned>" +
                        "</group></iq>",
                escapeXml(iqId), GROUP_NS,
                room.groupId(),
                escapeXml(room.jid()),
                escapeXml(room.name()),
                escapeXml(room.description() != null ? room.description() : ""),
                room.visibility().xmlValue(),
                room.maxMembers(),
                room.memberCount(),
                room.createdAt().toString(),
                escapeXml(session.getJid()),
                escapeXml(creator.jid()),
                escapeXml(creator.displayName()),
                escapeXml(creator.avatarUrl()),
                settings.onlyAdminsCanSend(),
                settings.onlyAdminsCanEditMeta(),
                settings.onlyAdminsCanAdd(),
                settings.membershipApproval(),
                settings.announcementMode(),
                settings.allowHistory(),
                settings.historyMaxMessages(),
                settings.disappearingSeconds(),
                info.isBanned(),
                info.isMember()
        );

        session.writeXML(response);
    }

    private void handleLinkPreview(ParsedGroupIQ p, String iqId,
                                   String userId, Session session) {
        if (p.linkToken() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        // Look up link and group WITHOUT consuming the link or modifying anything
        GroupPreview preview= repository.previewGroupViaLink(p.linkToken(), userId);

        if (preview == null) {
            sendIQError(session, iqId, "item-not-found");
            return;
        }

        String xml = String.format(
                "<iq type='result' id='%s'>" +
                        "<group xmlns='%s' action='link_preview'>" +
                        "<group_id>%s</group_id>" +
                        "<name>%s</name>" +
                        "<description>%s</description>" +
                        "<avatar_url>%s</avatar_url>" +
                        "<visibility>%s</visibility>" +
                        "<member_count>%d</member_count>" +
                        "<max_members>%d</max_members>" +
                        "<is_member>%b</is_member>" +
                        "<is_banned>%b</is_banned>" +
                        "<link_valid>%b</link_valid>" +
                        "<link_expires_at>%s</link_expires_at>" +
                        "<creator_display_name>%s</creator_display_name>" +
                        "</group></iq>",
                escapeXml(iqId), GROUP_NS,
                preview.groupId(),
                escapeXml(preview.name()),
                escapeXml(preview.description() != null ? preview.description() : ""),
                escapeXml(preview.avatarUrl() != null ? preview.avatarUrl() : ""),
                preview.visibility(),
                preview.memberCount(),
                preview.maxMembers(),
                preview.isMember(),
                preview.isBanned(),
                preview.linkValid(),
                preview.linkExpiresAt() != null ? preview.linkExpiresAt() : "",
                escapeXml(preview.creatorDisplayName() != null
                        ? preview.creatorDisplayName() : "")
        );

        session.writeXML(xml);
    }

    private void handleGetMetadata(ParsedGroupIQ p, String iqId,
                                   String userId, Session session) {
        if (p.groupId() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        // Only members can fetch metadata (privacy)
        GroupRepository.MemberRecord member =
                repository.getMember(p.groupId(), userId);
        if (member == null) {
            sendIQError(session, iqId, "forbidden");
            return;
        }

        GroupRoom room = registry.getOrLoad(p.groupId());
        if (room == null) {
            sendIQError(session, iqId, "item-not-found");
            return;
        }

        GroupSettings s = room.getSettings();
        DatabaseManager.UserRecord creator = db.getUserByUserId(room.getCreatorUserId());

        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<group xmlns='%s' action='metadata'>" +
                        "<group_id>%s</group_id>" +
                        "<name>%s</name>" +
                        "<description>%s</description>" +
                        "<avatar_url>%s</avatar_url>" +
                        "<creator_jid>%s</creator_jid>" +
                        "<visibility>%s</visibility>" +
                        "<max_members>%d</max_members>" +
                        "<member_count>%d</member_count>" +
                        "<settings>" +
                        "<only_admins_send>%b</only_admins_send>" +
                        "<only_admins_meta>%b</only_admins_meta>" +
                        "<only_admins_add>%b</only_admins_add>" +
                        "<membership_approval>%b</membership_approval>" +
                        "<announcement_mode>%b</announcement_mode>" +
                        "<allow_history>%b</allow_history>" +
                        "<history_max_messages>%d</history_max_messages>" +
                        "<disappearing_seconds>%d</disappearing_seconds>" +
                        "</settings>" +
                        "</group></iq>",
                escapeXml(iqId), GROUP_NS,
                room.getGroupId(),
                escapeXml(room.getName()),
                escapeXml(room.getDescription() != null ? room.getDescription() : ""),
                escapeXml(room.getAvatarUrl() != null ? room.getAvatarUrl() : ""),
                escapeXml(creator != null ? creator.jid() : ""),
                room.getVisibility().xmlValue(),
                room.getMaxMembers(),
                room.getMemberCount(),
                s.onlyAdminsCanSend(),
                s.onlyAdminsCanEditMeta(),
                s.onlyAdminsCanAdd(),
                s.membershipApproval(),
                s.announcementMode(),
                s.allowHistory(),
                s.historyMaxMessages(),
                s.disappearingSeconds()
        ));
    }


    public record GroupPreview(
            String groupId, String name, String description, String avatarUrl,
            String visibility, int memberCount, int maxMembers,
            boolean isMember, boolean isBanned, boolean linkValid,
            Instant linkExpiresAt, String creatorDisplayName
    ) {}

    // =========================================================================
    // Presence: join/leave room
    // =========================================================================

    /**
     * XEP-0045 §7.2 - Entering a Room.
     * <p>
     * Client sends:
     *   <presence to='room@conference.domain/nickname'>
     *     <x xmlns='http://jabber.org/protocol/muc'/>
     *   </presence>
     * <p>
     * Server responds with full join sequence:
     *   1. Presence of all existing occupants
     *   2. User's own presence (with 110 status code)
     *   3. Discussion history (configurable)
     *   4. Room subject
     */
    private void handlePresence(StartElement element, XMLEventReader reader,
                                 Session session) {
        String to       = getAttr(element, "to");
        String type     = getAttr(element, "type");

        logger.info("TO IS: "+to+ " and type is: "+type);
        if (to == null || !to.contains("@")) {
            consumeElement(reader);
            return;
        }

        String groupJid = bareJid(to);
        String nickname = extractResource(to);
        String groupId  = groupIdFromJid(groupJid);

        consumeElement(reader);

        if ("unavailable".equals(type)) {
            handleLeaveRoom(groupId, session);
        } else {
            handleJoinRoom(groupId, nickname, session);
        }
    }

    /**
     * This handles the situation where by a user sends a {@code <presence> </presence>} stanza
     * to the server to actually joined the group after being a member of the group
     * @param groupId The groupId to become an occupant of
     * @param nickname The preferred nickname to use
     * @param session The current user session
     */
    private void handleJoinRoom(String groupId, String nickname,
                                 Session session) {
        String userId = extractUserId(session.getContactId());
        try {
            GroupRoom room = registry.getOrLoad(groupId);
            if (room == null) {
                sendPresenceError(session,
                        groupId + "@" + mucDomain + "/" + nickname,
                        "item-not-found");
                return;
            }

            // Verify membership
            GroupRepository.MemberRecord member = repository.getMember(groupId, userId);
            if (member == null || member.affiliation() == Affiliation.OUTCAST) {
                sendPresenceError(session,
                        room.getJid() + "/" + nickname,
                        "registration-required");
                return;
            }

            // Resolve nickname
            String resolvedNickname = (nickname != null && !nickname.isBlank())
                    ? nickname
                    : member.nickname() != null
                        ? member.nickname()
                        : userId;

            // Already in the room? Update presence
            Occupant existing = room.getOccupant(userId);
            if (existing != null) {
                // Just update session uid in case of resource change
                Occupant updated = new Occupant(
                        userId, session.getContactId(),
                        resolvedNickname, session.getUid(),
                        existing.affiliation(), existing.role(),
                        existing.joinedAt()
                );
                room.addOccupant(updated);
                return;
            }

            // Create occupant
            Role role = Role.fromAffiliation(member.affiliation(), false);
            Occupant occupant = new Occupant(
                    userId, session.getContactId(),
                    resolvedNickname, session.getUid(),
                    member.affiliation(), role,
                    Instant.now()
            );
            room.addOccupant(occupant);

            // XEP-0045 §7.2 join sequence
            presenceBroadcaster.broadcastJoin(room, occupant);

            // Send history
            sendDiscussionHistory(room, occupant, session);

            logger.info("User joined group: " + userId
                    + " → " + groupId
                    + " occupants=" + room.getOccupantCount());

        } catch (MucException e) {
            sendPresenceError(session,
                    groupId + "@" + mucDomain + "/" + nickname,
                    e.getCode().xmppCondition);
        }
    }

    private void handleLeaveRoom(String groupId, Session session) {
        String userId = extractUserId(session.getContactId());

        GroupRoom room = registry.getIfActive(groupId);
        if (room == null) return;

        Occupant occupant = room.removeOccupant(userId);
        if (occupant != null) {
            presenceBroadcaster.broadcastLeave(room, occupant,
                    "left", null);
        }

        // Evict empty rooms after grace period
        if (room.getOccupantCount() == 0) {
            // TODO: schedule eviction via timer (15 minutes idle)
        }

        logger.info("User left group: " + userId + " ← " + groupId);
    }

    // =========================================================================
    // Message: group chat
    // =========================================================================

    /**
     * XEP-0045 §7.4 - Sending a Message to all Occupants.
     * <p>
     * Client sends:
     *   <message to='room@conference.domain' type='groupchat'>
     *     <body>Hello group</body>
     *   </message>
     * <p>
     * Server fans out to all occupants with from='room@conference.domain/nick'.
     */
    private void handleMessage(StartElement element, XMLEventReader reader,
                                Session session) {
        String to   = getAttr(element, "to");
        String type = getAttr(element, "type");
        String id   = getAttr(element, "id");

        if (!"groupchat".equals(type) || to == null) {
            consumeElement(reader);
            return;
        }

        String groupJid = bareJid(to);
        String groupId  = groupIdFromJid(groupJid);
        String userId   = extractUserId(session.getContactId());

        if (id == null) id = UUID.randomUUID().toString();

        // Parse message content (body + encrypted payload)
        ParsedGroupMessage parsed = parseGroupMessage(reader);

        GroupRoom room = registry.getOrLoad(groupId);
        if (room == null) {
            consumeElement(reader);
            return;
        }

        // Verify user is in the room
        Occupant sender = room.getOccupant(userId);
        if (sender == null) {
            sendMessageError(session, id, "not-acceptable",
                    "You must join the room first");
            return;
        }

        // Check role
        if (!sender.role().canSpeak()) {
            sendMessageError(session, id, "forbidden",
                    "You don't have permission to speak");
            return;
        }

        // Check announcement mode
        if (room.getSettings().announcementMode()
                && !sender.affiliation().canModerate()) {
            sendMessageError(session, id, "forbidden",
                    "Announcement-only mode");
            return;
        }

        // Check mute
        GroupRepository.MemberRecord memberRecord =
                repository.getMember(groupId, userId);
        if (memberRecord != null && memberRecord.mutedUntil() != null
                && memberRecord.mutedUntil().isAfter(Instant.now())) {
            sendMessageError(session, id, "forbidden", "You are muted");
            return;
        }

        // Persist to history if configured
        if (room.getSettings().allowHistory()) {
            persistMessage(room, sender, id, parsed);
        }

        // Build the fanout stanza
        String stanza = String.format(
            "<message id='%s' from='%s' to='%%s' type='groupchat'>" +
            "%s" +
            "</message>",
            escapeXml(id),
            escapeXml(sender.roomJid(room.getJid())),
            parsed.payload()
        );

        // Personalize per-recipient and fan out
        for (Occupant occupant : room.getOccupants()) {
            String personalized = String.format(
                "<message id='%s' from='%s' to='%s' type='groupchat'>%s</message>",
                escapeXml(id),
                escapeXml(sender.roomJid(room.getJid())),
                escapeXml(occupant.userJid()),
                parsed.payload()
            );

            if (!occupant.userId().equals(userId)) {
                // Async delivery to other occupants
                fanoutService.fanoutMessage(room, userId, personalized);
            }
        }

        // Echo back to sender with confirmed id (XEP-0045 §7.4)
        String echo = String.format(
            "<message id='%s' from='%s' to='%s' type='groupchat'>%s</message>",
            escapeXml(id),
            escapeXml(sender.roomJid(room.getJid())),
            escapeXml(sender.userJid()),
            parsed.payload()
        );
        session.writeXML(echo);
    }

    // =========================================================================
    // IQ: group operations (custom namespace)
    // =========================================================================

    /**
     * Handles custom group operations via IQ stanzas.
     * <p>
     * Examples:
     *   <iq type='set'><create xmlns='urn:xmpp:group:0' name='...'/></iq>
     *   <iq type='set'><add xmlns='urn:xmpp:group:0' jid='...'/></iq>
     *   <iq type='set'><invite-link xmlns='urn:xmpp:group:0'/></iq>
     */
    private void handleIQ(StartElement element, XMLEventReader reader,
                           Session session) {
        String iqId   = getAttr(element, "id");
        String iqType = getAttr(element, "type");

        ParsedGroupIQ parsed = parseGroupIQ(reader);
        if (parsed == null || parsed.action() == null) {
            sendIQError(session, iqId, "bad-request");
            return;
        }

        String userId = extractUserId(session.getContactId());

        try {
            switch (parsed.action()) {
                case "create" -> handleCreateGroup(parsed, iqId, userId, session);
                case "add"    -> handleAddMember(parsed, iqId, userId, session);
                case "remove" -> handleRemoveMember(parsed, iqId, userId, session);
                case "promote" -> handlePromote(parsed, iqId, userId, session);
                case "demote"  -> handleDemote(parsed, iqId, userId, session);
                case "transfer" -> handleTransfer(parsed, iqId, userId, session);
                case "config"   -> handleConfig(parsed, iqId, userId, session);
                case "metadata" -> handleMetadata(parsed, iqId, userId, session);
                case "invite"   -> handleInvite(parsed, iqId, userId, session);
                case "accept_invite" -> handleAcceptInvite(parsed, iqId, userId, session);
                case "reject_invite" -> handleRejectInvite(parsed, iqId, userId, session);
                case "create_link"   -> handleCreateLink(parsed, iqId, userId, session);
                case "join_link"     -> handleJoinViaLink(parsed, iqId, userId, session);
                case "revoke_link"   -> handleRevokeLink(parsed, iqId, userId, session);
                case "list_members"  -> handleListMembers(parsed, iqId, userId, session);
                case "destroy"       -> handleDestroyGroup(parsed, iqId, userId, session);
                case "preview_link" -> handleLinkPreview1(parsed, iqId, userId, session);
                case "get_metadata" -> handleGetMetadata(parsed, iqId, userId, session);
                default -> sendIQError(session, iqId, "feature-not-implemented");
            }
        } catch (MucException e) {
            sendIQError(session, iqId, e.getCode().xmppCondition);
        } catch (Exception e) {
            logger.severe("IQ error: " + e.getMessage());
            sendIQError(session, iqId, "internal-server-error");
        }
    }

    private void handleCreateGroup(ParsedGroupIQ p, String iqId,
                                    String userId, Session session) {
        // Creates the group and add the creator as a owner in group_member table
        GroupRoom room = groupService.createGroup(
                p.name(), p.description(), userId,
                p.visibility() != null
                    ? GroupVisibility.fromString(p.visibility())
                    : GroupVisibility.PRIVATE,
                p.maxMembers() > 0 ? p.maxMembers() : 256
        );

        GroupRepository.MemberRecord member = repository.getMember(room.getGroupId(), userId);
        if(member == null
            || member.affiliation() == Affiliation.OUTCAST){
            sendPresenceError(session, room.getJid() + "/",
                    "registration-required");
            return;
        }

        String resolvedNickname = (member.nickname() != null && member.nickname().isBlank())
                ? member.nickname()
                : session.getUid();

        Occupant existing = room.getOccupant(session.getUid());
        if(existing != null){
            Occupant updated = new Occupant(
                    session.getUid(), session.getContactId(),
                    resolvedNickname, session.getUid(),
                    existing.affiliation(), existing.role(),
                    existing.joinedAt()
            );
            room.addOccupant(updated);
            return;
        }

        Role role = Role.fromAffiliation(member.affiliation(), false);
        Occupant occupant = new Occupant(
                session.getUid(), session.getContactId(),
                resolvedNickname, session.getUid(),
                member.affiliation(), role, Instant.now()
        );
        room.addOccupant(occupant);

        // Return COMPLETE group info so client doesn't need follow-up queries
        GroupSettings settings = room.getSettings();
        DatabaseManager.UserRecord creator = db.getUserByUserId(userId);

        String response = String.format(
                "<iq type='result' id='%s'>" +
                        "<group xmlns='%s' action='created'>" +
                        "<group_id>%s</group_id>" +
                        "<jid>%s</jid>" +
                        "<name>%s</name>" +
                        "<description>%s</description>" +
                        "<visibility>%s</visibility>" +
                        "<max_members>%d</max_members>" +
                        "<member_count>1</member_count>" +
                        "<created_at>%s</created_at>" +
                        "<creator_jid>%s</creator_jid>" +
                        "<settings>" +
                        "<only_admins_send>%b</only_admins_send>" +
                        "<only_admins_meta>%b</only_admins_meta>" +
                        "<only_admins_add>%b</only_admins_add>" +
                        "<membership_approval>%b</membership_approval>" +
                        "<announcement_mode>%b</announcement_mode>" +
                        "<allow_history>%b</allow_history>" +
                        "<history_max_messages>%d</history_max_messages>" +
                        "<disappearing_seconds>%d</disappearing_seconds>" +
                        "</settings>" +
                        "<my_affiliation>owner</my_affiliation>" +
                        "<members>" +
                        "<member jid='%s' display_name='%s' " +
                        "avatar_url='%s' affiliation='owner' joined_at='%s'/>" +
                        "</members>" +
                        "</group></iq>",
                escapeXml(iqId), GROUP_NS,
                room.getGroupId(),
                escapeXml(room.getJid()),
                escapeXml(room.getName()),
                escapeXml(room.getDescription() != null ? room.getDescription() : ""),
                room.getVisibility().xmlValue(),
                room.getMaxMembers(),
                room.getCreatedAt().toString(),
                escapeXml(session.getJid()),
                settings.onlyAdminsCanSend(),
                settings.onlyAdminsCanEditMeta(),
                settings.onlyAdminsCanAdd(),
                settings.membershipApproval(),
                settings.announcementMode(),
                settings.allowHistory(),
                settings.historyMaxMessages(),
                settings.disappearingSeconds(),
                escapeXml((creator.jid() == null || creator.jid().isBlank()) ? session.getJid() : creator.jid()),
                escapeXml(creator.displayName() != null
                        ? creator.displayName() : ""),
                escapeXml(creator.avatarUrl() != null
                        ? creator.avatarUrl() : ""),
                Instant.now().toString()
        );

        session.writeXML(response);

        //TODO: A system message should be sent also to the creator of the group

        logger.info("Group created: groupId=" + room.getGroupId() + " creator=" + userId);
    }

    private void handleAddMember(ParsedGroupIQ p, String iqId,
                                  String userId, Session session) {
        membershipService.addMember(
                p.groupId(), userId,
                extractUserId(p.targetJid()),
                p.targetJid(),
                Affiliation.MEMBER
        );

        session.writeXML(buildSuccessResult(iqId, "member_added"));
    }

    private void handleRemoveMember(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        membershipService.removeMember(p.groupId(), userId,
                extractUserId(p.targetJid()), p.reason());
        session.writeXML(buildSuccessResult(iqId, "member_removed"));
    }

    private void handlePromote(ParsedGroupIQ p, String iqId,
                                String userId, Session session) {
        membershipService.promoteToAdmin(p.groupId(), userId,
                extractUserId(p.targetJid()));
        session.writeXML(buildSuccessResult(iqId, "promoted"));
    }

    private void handleDemote(ParsedGroupIQ p, String iqId,
                               String userId, Session session) {
        membershipService.demoteToMember(p.groupId(), userId,
                extractUserId(p.targetJid()));
        session.writeXML(buildSuccessResult(iqId, "demoted"));
    }

    private void handleTransfer(ParsedGroupIQ p, String iqId,
                                 String userId, Session session) {
        membershipService.transferOwnership(p.groupId(), userId,
                extractUserId(p.targetJid()));
        session.writeXML(buildSuccessResult(iqId, "ownership_transferred"));
    }

    private void handleConfig(ParsedGroupIQ p, String iqId,
                               String userId, Session session) {
        GroupSettings newSettings = new GroupSettings(
                p.onlyAdminsCanSend(),
                p.onlyAdminsCanEditMeta(),
                p.onlyAdminsCanAdd(),
                p.membershipApproval(),
                p.announcementMode(),
                p.allowHistory(),
                p.historyMaxMessages() > 0 ? p.historyMaxMessages() : 50,
                p.disappearingSeconds()
        );

        groupService.updateSettings(p.groupId(), userId, newSettings);
        session.writeXML(buildSuccessResult(iqId, "config_updated"));
    }

    private void handleMetadata(ParsedGroupIQ p, String iqId,
                                 String userId, Session session) {
        groupService.updateMetadata(p.groupId(), userId,
                p.name(), p.description(), p.avatarUrl());
        session.writeXML(buildSuccessResult(iqId, "metadata_updated"));
    }

    private void handleInvite(ParsedGroupIQ p, String iqId,
                               String userId, Session session) {
        String invitationId = invitationService.sendInvitation(
                p.groupId(), userId,
                extractUserId(p.targetJid()),
                p.targetJid(),
                p.reason()
        );

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='invited'>" +
            "<invitation_id>%s</invitation_id>" +
            "</group></iq>",
            escapeXml(iqId), GROUP_NS, invitationId
        ));
    }

    private void handleAcceptInvite(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        invitationService.acceptInvitation(p.invitationId(), userId);
        session.writeXML(buildSuccessResult(iqId, "invite_accepted"));
    }

    private void handleRejectInvite(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        invitationService.rejectInvitation(p.invitationId(), userId);
        session.writeXML(buildSuccessResult(iqId, "invite_rejected"));
    }

    private void handleCreateLink(ParsedGroupIQ p, String iqId,
                                   String userId, Session session) {
        InvitationService.JoinLink link = invitationService.createJoinLink(
                p.groupId(), userId,
                p.oneTime(), p.maxUses(), p.expiresInHours());

        logger.info("The url format is: "+link.url());
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='link_created'>" +
            "<token>%s</token>" +
            "<url>%s</url>" +
            "<one_time>%b</one_time>" +
            "%s" +
            "</group></iq>",
            escapeXml(iqId), GROUP_NS,
            link.token(),
            escapeXml(link.url()),
            link.oneTime(),
            link.expiresAt() != null
                ? "<expires_at>" + link.expiresAt() + "</expires_at>"
                : ""
        ));
    }


    /**
     * This handles the situation where by a client is invited to join
     * a group via a link, it might be through social media e.t.c
     * @param p The parsed info/query stanza
     * @param iqId The iq id
     * @param userId The current session uid
     * @param session The current session
     */
    private void handleJoinViaLink(ParsedGroupIQ p, String iqId,
                                    String userId, Session session) {
        String groupId = invitationService.joinViaLink(
                p.linkToken(), userId, session.getContactId());

        // Since the client already has the group info before they can
        // actually joined the group via this handler, only groupId and the
        // members information
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='joined'>" +
            "<group_id>%s</group_id>" +
            "</group></iq>",
            escapeXml(iqId), GROUP_NS, groupId
        ));
    }

    private void handleRevokeLink(ParsedGroupIQ p, String iqId,
                                   String userId, Session session) {
        invitationService.revokeLink(p.linkToken(), userId);
        session.writeXML(buildSuccessResult(iqId, "link_revoked"));
    }

    private void handleListMembers(ParsedGroupIQ p, String iqId,
                                    String userId, Session session) {
        logger.info("Listing members of the group: "+p.groupId());
        // Verify membership
        if (repository.getMember(p.groupId(), userId) == null) {
            sendIQError(session, iqId, "forbidden");
            return;
        }

        var members = repository.listMembers(p.groupId(),
                p.limit() > 0 ? p.limit() : 100,
                p.offset());

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<group xmlns='%s' action='members'>",
            escapeXml(iqId), GROUP_NS
        ));

        for (var m : members) {
            xml.append(String.format(
                "<member jid='%s' affiliation='%s' joined_at='%s'/>",
                escapeXml(m.userJid()),
                m.affiliation().xmlValue(),
                m.joinedAt()
            ));
        }

        xml.append("</group></iq>");
        session.writeXML(xml.toString());
    }

    private void handleDestroyGroup(ParsedGroupIQ p, String iqId,
                                     String userId, Session session) {
        groupService.destroyGroup(p.groupId(), userId, p.reason());
        session.writeXML(buildSuccessResult(iqId, "destroyed"));
    }

    // =========================================================================
    // Parsing helpers
    // =========================================================================

    private ParsedGroupMessage parseGroupMessage(XMLEventReader reader) {
        StringBuilder payload = new StringBuilder();
        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    payload.append(eventToString(event));
                } else if (event.isEndElement()) {
                    depth--;
                    if (depth > 0) payload.append(eventToString(event));
                } else if (event.isCharacters()) {
                    payload.append(escapeXml(event.asCharacters().getData()));
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("parseGroupMessage error: " + e.getMessage());
        }

        return new ParsedGroupMessage(payload.toString());
    }

    private ParsedGroupIQ parseGroupIQ(XMLEventReader reader) {
        String action = null, groupId = null, targetJid = null;
        String name = null, description = null, avatarUrl = null;
        String reason = null, invitationId = null, linkToken = null;
        String visibility = null;
        int maxMembers = 0, limit = 0, offset = 0, historyMax = 0;
        int disappearingSeconds = 0;
        boolean onlyAdminsSend = false, onlyAdminsMeta = true;
        boolean onlyAdminsAdd = false, approval = false;
        boolean announcement = false, allowHistory = true;
        boolean oneTime = false;
        Integer maxUses = null;
        Long expiresInHours = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name1 = se.getName().getLocalPart();
                    String ns    = se.getName().getNamespaceURI();

                    if ("group".equals(name1) && GROUP_NS.equals(ns)) {
                        action = getAttr(se, "action");
                    }

                    switch (name1) {
                        case "group_id"      -> groupId   = readText(reader);
                        case "target_jid", "jid" -> targetJid = readText(reader);
                        case "name"          -> name      = readText(reader);
                        case "description"   -> description = readText(reader);
                        case "avatar_url"    -> avatarUrl = readText(reader);
                        case "reason"        -> reason    = readText(reader);
                        case "invitation_id" -> invitationId = readText(reader);
                        case "token"         -> linkToken = readText(reader);
                        case "visibility"    -> visibility = readText(reader);
                        case "max_members"   -> maxMembers = parseIntSafe(readText(reader));
                        case "limit"         -> limit     = parseIntSafe(readText(reader));
                        case "offset"        -> offset    = parseIntSafe(readText(reader));
                        case "history_max_messages"
                                              -> historyMax = parseIntSafe(readText(reader));
                        case "disappearing_seconds"
                                              -> disappearingSeconds = parseIntSafe(readText(reader));
                        case "only_admins_send"
                                              -> onlyAdminsSend = parseBoolSafe(readText(reader));
                        case "only_admins_meta"
                                              -> onlyAdminsMeta = parseBoolSafe(readText(reader));
                        case "only_admins_add"
                                              -> onlyAdminsAdd = parseBoolSafe(readText(reader));
                        case "membership_approval"
                                              -> approval = parseBoolSafe(readText(reader));
                        case "announcement_mode"
                                              -> announcement = parseBoolSafe(readText(reader));
                        case "allow_history" -> allowHistory = parseBoolSafe(readText(reader));
                        case "one_time"      -> oneTime = parseBoolSafe(readText(reader));
                        case "max_uses"      -> maxUses = parseIntSafe(readText(reader));
                        case "expires_in_hours" -> expiresInHours = parseLongSafe(readText(reader));
                    }

                    depth--;
                } else if (event.isEndElement()) {
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("parseGroupIQ error: " + e.getMessage());
            return null;
        }

        return new ParsedGroupIQ(
                action, groupId, targetJid, name, description, avatarUrl,
                reason, invitationId, linkToken, visibility,
                maxMembers, limit, offset, historyMax, disappearingSeconds,
                onlyAdminsSend, onlyAdminsMeta, onlyAdminsAdd,
                approval, announcement, allowHistory,
                oneTime, maxUses, expiresInHours
        );
    }

    // =========================================================================
    // History delivery
    // =========================================================================

    private void sendDiscussionHistory(GroupRoom room, Occupant occupant,
                                        Session session) {
        if (!room.getSettings().allowHistory()) return;

        // TODO: implement history fetch from group_message_history
        // and replay to the joining user with <delay> elements
    }

    private void persistMessage(GroupRoom room, Occupant sender,
                                 String messageId, ParsedGroupMessage parsed) {
        // Asynchronously persist to group_message_history
        // ... uses GroupMessageRepository which I'll skip here for brevity
    }

    // =========================================================================
    // Helpers
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

    private void sendPresenceError(Session session, String from,
                                     String condition) {
        session.writeXML(String.format(
            "<presence from='%s' type='error'>" +
            "<error type='cancel'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "</error></presence>",
            escapeXml(from), condition
        ));
    }

    private void sendMessageError(Session session, String id,
                                    String condition, String text) {
        session.writeXML(String.format(
            "<message id='%s' type='error'>" +
            "<error type='%s'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "<text>%s</text>" +
            "</error></message>",
            escapeXml(id),
            "forbidden".equals(condition) ? "auth" : "modify",
            condition, escapeXml(text)
        ));
    }

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

    private String eventToString(XMLEvent event) {
        if (event.isStartElement()) {
            StartElement se = event.asStartElement();
            StringBuilder sb = new StringBuilder("<");
            sb.append(se.getName().getLocalPart());
            se.getAttributes().forEachRemaining(a -> {
                Attribute attr = (Attribute) a;
                sb.append(" ").append(attr.getName().getLocalPart())
                  .append("='").append(escapeXml(attr.getValue()))
                  .append("'");
            });
            sb.append(">");
            return sb.toString();
        }
        if (event.isEndElement()) {
            return "</" + event.asEndElement().getName().getLocalPart() + ">";
        }
        return "";
    }

    private String bareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    private String extractResource(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? null : jid.substring(slash + 1);
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private String groupIdFromJid(String jid) {
        return extractUserId(jid);
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (Exception e) { return 0L; }
    }

    private boolean parseBoolSafe(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Records
    // =========================================================================

    private record ParsedGroupMessage(String payload) {}

    private record ParsedGroupIQ(
            String action, String groupId, String targetJid,
            String name, String description, String avatarUrl,
            String reason, String invitationId, String linkToken,
            String visibility,
            int maxMembers, int limit, int offset,
            int historyMaxMessages, int disappearingSeconds,
            boolean onlyAdminsCanSend, boolean onlyAdminsCanEditMeta,
            boolean onlyAdminsCanAdd, boolean membershipApproval,
            boolean announcementMode, boolean allowHistory,
            boolean oneTime, Integer maxUses, Long expiresInHours
    ) {}
}