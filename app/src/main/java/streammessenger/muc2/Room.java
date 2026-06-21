package streammessenger.muc;

import com.example.xmpp.muc.*;
import com.example.xmpp.muc.server.store.RoomRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative in-memory state for one room.
 *
 * KEY DESIGN RULES (memorise these):
 *   1. Every command returns List<Outbound> — it NEVER touches the network.
 *      The MucService dispatches the result. This makes Room unit-testable.
 *   2. Membership == presence in `occupantsByNick`. Removing someone from that
 *      map automatically excludes them from all future fan-outs.
 *   3. Affiliation changes are persisted (write-through) BEFORE broadcasting.
 *   4. Mutating commands are `synchronized` on the room → per-room concurrency.
 */
public final class Room {

    /** One outgoing stanza, already serialized, addressed to one recipient. */
    public static final class Outbound {
        public final String toJid;
        public final String xml;
        public Outbound(String toJid, String xml) { this.toJid = toJid; this.xml = xml; }
    }

    private final String roomJid;            // <uuid>@conference.example.com
    private final RoomConfig config;
    private final RoomRepository repo;
    private final RoomHistory history;

    /** Persistent ledger: bareJid -> affiliation (mirrors the DB). */
    private final Map<String, Affiliation> affiliations = new ConcurrentHashMap<>();
    /** Present sessions: nick(=uid) -> Occupant. */
    private final Map<String, Occupant> occupantsByNick = new ConcurrentHashMap<>();

    private volatile String subject = "";

    public Room(String roomJid, String ownerBareJid, RoomConfig config, RoomRepository repo) {
        this.roomJid = roomJid;
        this.config = config;
        this.repo = repo;
        this.history = new RoomHistory(config.historyOnJoin());
        this.affiliations.put(bare(ownerBareJid), Affiliation.OWNER);
    }

    /** Rebuild a room from a persisted record + its affiliation ledger. */
    public static Room fromRecord(RoomRepository.RoomRecord rec, RoomRepository repo) {
        RoomConfig cfg = RoomConfig.builder()
                .name(rec.name).description(rec.description)
                .membersOnly(rec.membersOnly).persistent(rec.persistent)
                .moderated(rec.moderated).nonAnonymous(rec.nonAnonymous)
                .maxOccupants(rec.maxOccupants).build();
        Room room = new Room(rec.roomJid, "__none__", cfg, repo);
        room.affiliations.clear();
        room.affiliations.putAll(repo.loadAffiliations(rec.roomJid));
        return room;
    }

    public String roomJid() { return roomJid; }
    public RoomConfig config() { return config; }
    public Affiliation affiliationOf(String jid) {
        return affiliations.getOrDefault(bare(jid), Affiliation.NONE);
    }

    // ───────────────────────────── JOIN ──────────────────────────────────

    public synchronized List<Outbound> join(String realJid, String nick) throws MucException {
        Affiliation aff = affiliationOf(realJid);

        if (aff == Affiliation.OUTCAST)                                  // GATE 1: banned
            throw MucException.forbidden("You are banned from this group");
        if (config.membersOnly() && !aff.atLeast(Affiliation.MEMBER))    // GATE 2: members-only
            throw MucException.registrationRequired("This group is members-only");
        if (occupantsByNick.size() >= config.maxOccupants())             // GATE 3: full
            throw MucException.forbidden("Group is full");

        Role role = deriveRole(aff);
        Occupant joining = new Occupant(realJid, roomJid, nick, role, aff);
        occupantsByNick.put(nick, joining);

        List<Outbound> out = new ArrayList<>();

        // (a) tell existing members the newcomer arrived
        for (Occupant m : occupantsByNick.values()) {
            if (m == joining) continue;
            out.add(new Outbound(m.realJid(),
                Stanzas.presence(m.realJid(), roomJid, joining.nick(),
                                 joining.affiliation(), joining.role(), false)));
        }
        // (b) tell the newcomer about everyone (incl. self with status 110)
        for (Occupant m : occupantsByNick.values()) {
            boolean self = (m == joining);
            out.add(new Outbound(joining.realJid(),
                Stanzas.presence(joining.realJid(), roomJid, m.nick(),
                                 m.affiliation(), m.role(), self)));
        }
        // (c) replay recent history
        for (RoomHistory.Entry h : history.tail(config.historyOnJoin())) {
            String from = roomJid + "/" + h.fromNick;
            out.add(new Outbound(joining.realJid(), h.encrypted
                    ? Stanzas.opaqueMessage(joining.realJid(), from, h.body)
                    : Stanzas.groupMessage(joining.realJid(), from, h.body)));
        }
        // (d) push the subject
        out.add(new Outbound(joining.realJid(),
                Stanzas.subject(joining.realJid(), roomJid, subject)));
        return out;
    }

    // ───────────────────────────── LEAVE ─────────────────────────────────

    public synchronized List<Outbound> leave(String nick) {
        Occupant gone = occupantsByNick.remove(nick);
        if (gone == null) return List.of();
        gone.setRole(Role.NONE);
        List<Outbound> out = new ArrayList<>();
        for (Occupant m : occupantsByNick.values())
            out.add(new Outbound(m.realJid(),
                Stanzas.unavailable(m.realJid(), roomJid, gone.nick(),
                        gone.affiliation(), false, null, null)));
        out.add(new Outbound(gone.realJid(),
            Stanzas.unavailable(gone.realJid(), roomJid, gone.nick(),
                    gone.affiliation(), true, null, null)));
        return out;
    }

    // ──────────────────────── SEND MESSAGE (fan-out) ─────────────────────

    /** Plaintext group message. Excludes banned/removed users automatically. */
    public synchronized List<Outbound> relayMessage(String senderRealJid, String body)
            throws MucException {
        Occupant sender = requireOccupant(senderRealJid);
        if (sender.role() == Role.VISITOR)
            throw MucException.forbidden("You do not have voice in this group");

        history.append(sender.nick(), body, false);
        return fanOut(sender, body, false);
    }

    /** End-to-end encrypted message (server forwards opaque ciphertext). */
    public synchronized List<Outbound> relayEncrypted(String senderRealJid, String encryptedXml)
            throws MucException {
        Occupant sender = requireOccupant(senderRealJid);
        history.append(sender.nick(), encryptedXml, true);
        return fanOut(sender, encryptedXml, true);
    }

    private List<Outbound> fanOut(Occupant sender, String payload, boolean encrypted) {
        String fromAddr = roomJid + "/" + sender.nick();
        List<Outbound> out = new ArrayList<>();
        // ★ Loops ONLY over current occupants. Removed/banned users aren't here. ★
        for (Occupant r : occupantsByNick.values()) {
            out.add(new Outbound(r.realJid(), encrypted
                    ? Stanzas.opaqueMessage(r.realJid(), fromAddr, payload)
                    : Stanzas.groupMessage(r.realJid(), fromAddr, payload)));
        }
        return out;
    }

    // ─────────────────── MEMBERSHIP (add / remove / ban) ─────────────────

    public synchronized List<Outbound> grantMembership(String actor, String targetBare)
            throws MucException {
        requireAtLeast(actor, Affiliation.ADMIN);
        return setAffiliation(targetBare, Affiliation.MEMBER, null);
    }

    public synchronized List<Outbound> grantAdmin(String actor, String targetBare)
            throws MucException {
        requireAtLeast(actor, Affiliation.OWNER); // only owners make admins
        return setAffiliation(targetBare, Affiliation.ADMIN, null);
    }

    public synchronized List<Outbound> removeMember(String actor, String targetBare)
            throws MucException {
        requireAtLeast(actor, Affiliation.ADMIN);
        if (affiliationOf(targetBare) == Affiliation.OWNER
                && affiliationOf(actor) != Affiliation.OWNER)
            throw MucException.forbidden("Admins cannot remove an owner");
        return setAffiliation(targetBare, Affiliation.NONE, null);
    }

    public synchronized List<Outbound> ban(String actor, String targetBare, String reason)
            throws MucException {
        requireAtLeast(actor, Affiliation.ADMIN);
        return setAffiliation(targetBare, Affiliation.OUTCAST, reason);
    }

    /** Single choke-point for affiliation changes: persist, then handle ejection. */
    private List<Outbound> setAffiliation(String targetBare, Affiliation newAff, String reason) {
        targetBare = bare(targetBare);

        // 1) write-through to DB FIRST (durable truth before broadcast)
        if (newAff == Affiliation.NONE) {
            affiliations.remove(targetBare);
            repo.deleteAffiliation(roomJid, targetBare);
        } else {
            affiliations.put(targetBare, newAff);
            repo.upsertAffiliation(roomJid, targetBare, newAff);
        }

        List<Outbound> out = new ArrayList<>();
        Occupant present = findByBareJid(targetBare);

        // 2) if they're present and just lost the right to stay, eject them
        boolean mustEject = newAff == Affiliation.OUTCAST
                || (newAff == Affiliation.NONE && config.membersOnly());

        if (present != null && mustEject) {
            int code = (newAff == Affiliation.OUTCAST) ? 301 : 321;
            out.addAll(forceLeave(present, newAff, code, reason));
        } else if (present != null) {
            present.setAffiliation(newAff);  // still here, just re-broadcast role/aff
            for (Occupant m : occupantsByNick.values())
                out.add(new Outbound(m.realJid(),
                    Stanzas.presence(m.realJid(), roomJid, present.nick(),
                                     present.affiliation(), present.role(), m == present)));
        }
        return out;
    }

    // ───────────────────────────── KICK ──────────────────────────────────

    /** Transient removal (role->none) without touching affiliation. */
    public synchronized List<Outbound> kick(String actorRealJid, String nick, String reason)
            throws MucException {
        Occupant actor = requireModerator(actorRealJid);
        Occupant target = occupantsByNick.get(nick);
        if (target == null) throw MucException.itemNotFound("No such occupant");
        if (target != actor && target.affiliation().atLeast(actor.affiliation()))
            throw MucException.forbidden("Cannot kick a higher-privileged user");
        return forceLeave(target, target.affiliation(), 307, reason);
    }

    /** Shared eviction: drop from map, notify everyone + the evictee. */
    private List<Outbound> forceLeave(Occupant target, Affiliation finalAff,
                                      int statusCode, String reason) {
        occupantsByNick.remove(target.nick());   // ★ now excluded from all fan-outs
        target.setRole(Role.NONE);
        List<Outbound> out = new ArrayList<>();
        for (Occupant m : occupantsByNick.values())
            out.add(new Outbound(m.realJid(),
                Stanzas.unavailable(m.realJid(), roomJid, target.nick(),
                        finalAff, false, statusCode, reason)));
        out.add(new Outbound(target.realJid(),
            Stanzas.unavailable(target.realJid(), roomJid, target.nick(),
                    finalAff, true, statusCode, reason)));
        return out;
    }

    // ─────────────────────────── SUBJECT ─────────────────────────────────

    public synchronized List<Outbound> setSubject(String actorRealJid, String newSubject)
            throws MucException {
        requireModerator(actorRealJid);
        this.subject = newSubject == null ? "" : newSubject;
        List<Outbound> out = new ArrayList<>();
        for (Occupant m : occupantsByNick.values())
            out.add(new Outbound(m.realJid(), Stanzas.subject(m.realJid(), roomJid, subject)));
        return out;
    }

    // ─────────────────────────── PREVIEW ─────────────────────────────────

    public synchronized RoomPreview preview(String viewerBareJid) {
        boolean viewerIsMember = viewerBareJid != null
                && affiliationOf(viewerBareJid).atLeast(Affiliation.MEMBER);
        boolean reveal = config.nonAnonymous() || viewerIsMember;

        int members = 0;
        for (Affiliation a : affiliations.values())
            if (a == Affiliation.OWNER || a == Affiliation.ADMIN || a == Affiliation.MEMBER) members++;

        List<String> sample = new ArrayList<>();
        if (reveal)
            for (Occupant o : occupantsByNick.values()) {
                if (sample.size() >= 8) break;
                sample.add(o.nick());
            }

        return new RoomPreview(roomJid, config.name(), config.description(),
                members, occupantsByNick.size(), config.membersOnly(), sample);
    }

    // ─────────────────────────── helpers ─────────────────────────────────

    private Role deriveRole(Affiliation aff) {
        switch (aff) {
            case OWNER:
            case ADMIN:  return Role.MODERATOR;
            case MEMBER: return Role.PARTICIPANT;
            default:     return config.moderated() ? Role.VISITOR : Role.PARTICIPANT;
        }
    }
    private Occupant requireOccupant(String realJid) throws MucException {
        Occupant o = findByBareJid(bare(realJid));
        if (o == null) throw MucException.forbidden("You are not in this group");
        return o;
    }
    private Occupant requireModerator(String realJid) throws MucException {
        Occupant o = requireOccupant(realJid);
        if (o.role() != Role.MODERATOR) throw MucException.forbidden("Moderator required");
        return o;
    }
    private void requireAtLeast(String realJid, Affiliation min) throws MucException {
        if (!affiliationOf(realJid).atLeast(min))
            throw MucException.forbidden("Requires affiliation >= " + min.wire());
    }
    private Occupant findByBareJid(String bareJid) {
        for (Occupant o : occupantsByNick.values())
            if (bare(o.realJid()).equals(bareJid)) return o;
        return null;
    }
    private static String bare(String jid) {
        int s = jid.indexOf('/');
        return s < 0 ? jid : jid.substring(0, s);
    }
}