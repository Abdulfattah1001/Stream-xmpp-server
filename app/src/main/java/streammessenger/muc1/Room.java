package streammessenger.muc1;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side authoritative state for a single multi-user chat room.
 *
 * <h2>Threading</h2>
 * A room is a natural concurrency boundary. To avoid lock-ordering hell we make
 * every <i>mutating</i> command synchronized on the room instance. Commands are
 * short (in-memory mutations + stanza construction), so contention is bounded to
 * per-room granularity — different rooms run fully in parallel. Read-only views
 * use concurrent collections so presence broadcasts can iterate without locking.
 *
 * <h2>I/O separation</h2>
 * This class performs <b>no</b> network I/O. Every command returns a list of
 * {@link Outbound} packets. The {@link MucService} is responsible for actually
 * routing them. Benefits: deterministic unit tests, and the room never blocks on
 * a slow socket while holding its lock.
 */
public final class Room {

    /** A stanza ready to be routed: who it goes to + the payload builder. */
    public static final class Outbound {
        public final String toJid;
        public final StanzaSpec stanza;
        public Outbound(String toJid, StanzaSpec stanza) {
            this.toJid = toJid;
            this.stanza = stanza;
        }
    }

    private final String roomJid;            // room@conference.example.com
    private final RoomConfig config;

    /** Persistent ledger: bareJid -> affiliation. Survives leave/relogin. */
    private final Map<String, Affiliation> affiliations = new ConcurrentHashMap<>();

    /** Present sessions: nick -> Occupant. Nick is unique within a room. */
    private final Map<String, Occupant> occupantsByNick = new ConcurrentHashMap<>();

    /** Active invite links: token -> link metadata. */
    private final Map<String, InviteLink> inviteLinks = new ConcurrentHashMap<>();

    private volatile String subject = "";

    public Room(String roomJid, String ownerBareJid, RoomConfig config) {
        this.roomJid = roomJid;
        this.config = config;
        // The creator is always an owner. This is invariant #1.
        this.affiliations.put(bare(ownerBareJid), Affiliation.OWNER);
    }

    public String roomJid() { return roomJid; }
    public RoomConfig config() { return config; }
    public Collection<Occupant> occupants() { return occupantsByNick.values(); }

    // ───────────────────────────── JOIN ──────────────────────────────────

    /**
     * Handle a presence-to-join (XEP-0045 §7.2).
     *
     * @param realJid the joining user's full JID
     * @param nick    requested nickname (resource part of the join address)
     * @return packets to broadcast (existing occupants <-> newcomer)
     * @throws MucException with the appropriate XMPP error condition on rejection
     */
    public synchronized List<Outbound> join(String realJid, String nick) throws MucException {
        Affiliation aff = affiliationOf(realJid);

        // Invariant: outcasts can never enter.
        if (aff == Affiliation.OUTCAST)
            throw MucException.forbidden("You are banned from this room");

        // Members-only rooms: require at least MEMBER affiliation.
        if (config.membersOnly() && !aff.atLeast(Affiliation.MEMBER))
            throw MucException.registrationRequired("Room is members-only");

        // Nick collision: XEP-0045 says reject with <conflict/>.
        if (occupantsByNick.containsKey(nick))
            throw MucException.conflict("Nickname '" + nick + "' is in use");

        Role role = deriveRole(aff);
        Occupant joining = new Occupant(realJid, roomJid, nick, role, aff);
        occupantsByNick.put(nick, joining);

        List<Outbound> out = new ArrayList<>();

        // 1) Tell EXISTING occupants the newcomer arrived.
        for (Occupant existing : occupantsByNick.values()) {
            if (existing == joining) continue;
            out.add(presenceOf(joining, existing, /*self*/ false, /*statuses*/ List.of()));
        }
        // 2) Send the newcomer the FULL roster (everyone, including self).
        for (Occupant existing : occupantsByNick.values()) {
            boolean isSelf = existing == joining;
            List<MucStatus> codes = isSelf
                    ? List.of(MucStatus.SELF_PRESENCE)
                    : List.of();
            out.add(presenceOf(existing, joining, isSelf, codes));
        }
        // 3) Push current subject to the newcomer.
        out.add(subjectMessage(joining));

        return out;
    }

    // ──────────────────────── ADD MEMBER (admin) ─────────────────────────

    /**
     * Grant membership — the XEP-0045 equivalent of "add to group".
     *
     * @param actorRealJid who is performing the action (must be admin/owner)
     * @param targetBareJid the user being added
     */
    public synchronized List<Outbound> grantMembership(String actorRealJid, String targetBareJid)
            throws MucException {
        requireAtLeast(actorRealJid, Affiliation.ADMIN);
        return setAffiliation(actorRealJid, targetBareJid, Affiliation.MEMBER, null);
    }

    /**
     * Remove someone from the group: drop their affiliation to {@code none} and,
     * if they're currently present in a members-only room, force them out
     * (status 321). This mirrors WhatsApp "Remove from group".
     */
    public synchronized List<Outbound> removeMember(String actorRealJid, String targetBareJid)
            throws MucException {
        requireAtLeast(actorRealJid, Affiliation.ADMIN);
        // Cannot remove an owner unless you are an owner (privilege guard).
        if (affiliationOf(targetBareJid) == Affiliation.OWNER
                && affiliationOf(actorRealJid) != Affiliation.OWNER)
            throw MucException.forbidden("Admins cannot remove owners");

        return setAffiliation(actorRealJid, targetBareJid, Affiliation.NONE, null);
    }

    /** Ban a user (affiliation -> outcast). Also force-removes if present. */
    public synchronized List<Outbound> ban(String actorRealJid, String targetBareJid, String reason)
            throws MucException {
        requireAtLeast(actorRealJid, Affiliation.ADMIN);
        return setAffiliation(actorRealJid, targetBareJid, Affiliation.OUTCAST, reason);
    }

    /**
     * Core affiliation mutation + side effects. Single choke-point so all the
     * "if newly excluded, force them out" logic lives in one place.
     */
    private List<Outbound> setAffiliation(String actor, String targetBare,
                                          Affiliation newAff, String reason) {
        targetBare = bare(targetBare);
        if (newAff == Affiliation.NONE) affiliations.remove(targetBare);
        else affiliations.put(targetBare, newAff);

        List<Outbound> out = new ArrayList<>();

        // If the target is currently present and just lost the right to stay,
        // eject them and notify the room.
        boolean mustEject = (newAff == Affiliation.OUTCAST)
                || (newAff == Affiliation.NONE && config.membersOnly());

        Occupant present = findOccupantByBareJid(targetBare);
        if (present != null && mustEject) {
            MucStatus reasonCode = (newAff == Affiliation.OUTCAST)
                    ? MucStatus.BANNED : MucStatus.REMOVED_AFFILIATION_LOSS;
            out.addAll(forceLeave(present, actor, newAff, reasonCode, reason));
        } else if (present != null) {
            // Still present but affiliation changed -> broadcast updated presence.
            present.setAffiliation(newAff);
            for (Occupant o : occupantsByNick.values())
                out.add(presenceOf(present, o, o == present, List.of()));
        }
        return out;
    }

    // ───────────────────────────── KICK ──────────────────────────────────

    /**
     * Kick = transient removal (role -> none) without touching affiliation.
     * The user can rejoin immediately (unless also banned). XEP-0045 §8.2.
     */
    public synchronized List<Outbound> kick(String actorRealJid, String nick, String reason)
            throws MucException {
        Occupant actor = requireModerator(actorRealJid);
        Occupant target = occupantsByNick.get(nick);
        if (target == null) throw MucException.itemNotFound("No such occupant: " + nick);
        // Moderators cannot kick those with higher affiliation.
        if (target.affiliation().atLeast(actor.affiliation())
                && target != actor)
            throw MucException.forbidden("Cannot kick a higher-privileged user");

        return forceLeave(target, actorRealJid, target.affiliation(),
                MucStatus.KICKED, reason);
    }

    /**
     * Shared eviction routine. Removes the occupant and emits an "unavailable"
     * presence to everyone with the appropriate status code + actor + reason.
     */
    private List<Outbound> forceLeave(Occupant target, String actorJid,
                                      Affiliation finalAff,
                                      MucStatus reasonCode, String reason) {
        occupantsByNick.remove(target.nick());
        target.setRole(Role.NONE);

        List<Outbound> out = new ArrayList<>();
        for (Occupant o : occupantsByNick.values()) {
            out.add(unavailablePresence(target, o, false, finalAff,
                    reasonCode, actorJid, reason));
        }
        // Also tell the evicted user themselves (self-presence + reason code).
        out.add(unavailablePresence(target, target, true, finalAff,
                reasonCode, actorJid, reason));
        return out;
    }

    // ───────────────────────────── LEAVE ─────────────────────────────────

    /** Voluntary leave (presence type=unavailable to the room). */
    public synchronized List<Outbound> leave(String nick) {
        Occupant gone = occupantsByNick.remove(nick);
        if (gone == null) return List.of();
        gone.setRole(Role.NONE);

        List<Outbound> out = new ArrayList<>();
        for (Occupant o : occupantsByNick.values())
            out.add(unavailablePresence(gone, o, false, gone.affiliation(),
                    null, null, null));
        out.add(unavailablePresence(gone, gone, true, gone.affiliation(),
                null, null, null));
        return out;
    }

    // ─────────────────────────── helpers ─────────────────────────────────

    public Affiliation affiliationOf(String jid) {
        return affiliations.getOrDefault(bare(jid), Affiliation.NONE);
    }

    /** Map affiliation -> default role on join (XEP-0045 §5.1.1 table). */
    private Role deriveRole(Affiliation aff) {
        switch (aff) {
            case OWNER:
            case ADMIN:  return Role.MODERATOR;
            case MEMBER: return Role.PARTICIPANT;
            default:     return config.moderated() ? Role.VISITOR : Role.PARTICIPANT;
        }
    }

    private void requireAtLeast(String realJid, Affiliation min) throws MucException {
        if (!affiliationOf(realJid).atLeast(min))
            throw MucException.forbidden("Requires affiliation >= " + min.wire());
    }
    private Occupant requireModerator(String realJid) throws MucException {
        Occupant o = findOccupantByBareJid(bare(realJid));
        if (o == null || o.role() != Role.MODERATOR)
            throw MucException.forbidden("Moderator role required");
        return o;
    }

    private Occupant findOccupantByBareJid(String bareJid) {
        for (Occupant o : occupantsByNick.values())
            if (bare(o.realJid()).equals(bareJid)) return o;
        return null;
    }

    private static String bare(String jid) {
        int slash = jid.indexOf('/');
        return slash < 0 ? jid : jid.substring(0, slash);
    }

    // --- stanza builders (delegate to StanzaSpec, see §6.4) ---

    private Outbound presenceOf(Occupant about, Occupant recipient,
                                boolean self, List<MucStatus> statuses) {
        // Anonymity rule: only reveal real JID to recipients allowed to see it.
        String revealJid = config.nonAnonymous()
                || recipient.role() == Role.MODERATOR
                ? about.realJid() : null;
        return new Outbound(recipient.realJid(),
                StanzaSpec.occupantPresence(roomJid, about, revealJid, self, statuses));
    }

    private Outbound unavailablePresence(Occupant about, Occupant recipient, boolean self,
                                         Affiliation finalAff, MucStatus reasonCode,
                                         String actorJid, String reason) {
        return new Outbound(recipient.realJid(),
                StanzaSpec.occupantUnavailable(roomJid, about, finalAff, self,
                        reasonCode, actorJid, reason));
    }

    private Outbound subjectMessage(Occupant to) {
        return new Outbound(to.realJid(), StanzaSpec.subject(roomJid, subject));
    }

    // Expose invite-link map to the link feature (package-private).
    Map<String, InviteLink> inviteLinks() { return inviteLinks; }

    /**
     * Build a {@link RoomPreview}. Pure read — no mutation, no broadcast.
     *
     * @param viewerBareJid the prospective joiner (may be null/unknown for a public
     *                      disco). Used to decide how much to reveal.
     */
    public synchronized RoomPreview preview(String viewerBareJid) {
        boolean viewerIsMember =
                viewerBareJid != null && affiliationOf(viewerBareJid).atLeast(Affiliation.MEMBER);

        // Member count from the durable ledger (works even when room is empty/offline).
        int members = 0;
        for (Affiliation a : affiliations.values())
            if (a == Affiliation.MEMBER || a == Affiliation.ADMIN || a == Affiliation.OWNER) members++;

        // Sample a handful of members for the avatar row.
        // Privacy: in an anonymous room, only show names if the viewer is a member.
        List<RoomPreview.MemberPreview> sample = new ArrayList<>();
        boolean revealMembers = config.nonAnonymous() || viewerIsMember;
        if (revealMembers) {
            int limit = 8;
            for (Occupant o : occupantsByNick.values()) {
                if (sample.size() >= limit) break;
                sample.add(new RoomPreview.MemberPreview(o.nick(), /*avatarHint*/ null));
            }
        }

        // Teaser: only reveal message content if the room is non-anonymous/public
        // OR the viewer is already a member. Otherwise show nothing (privacy).
        /*List<String> teaser = new ArrayList<>();
        if (revealMembers) {
            for (RoomHistory.Entry e : history.tail(3))
                teaser.add(e.fromNick + ": " + e.body);
        }

        return new RoomPreview(
                roomJid,
                config.name(),
                config.description(),
                members,
                occupantsByNick.size(),
                config.membersOnly(),
                config.hasPassword(),
                subject,
                sample,
                teaser);*/
        return null;
    }
}