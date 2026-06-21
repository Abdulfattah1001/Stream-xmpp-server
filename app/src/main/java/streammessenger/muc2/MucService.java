package streammessenger.muc;

import com.example.xmpp.muc.*;
import com.example.xmpp.muc.server.net.*;
import com.example.xmpp.muc.server.store.RoomRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Owns all live rooms, creates them, lazily loads them from the DB after a
 * restart, and DISPATCHES the Outbound stanzas the engine produces.
 */
public final class MucService {

    private final String serviceDomain;            // conference.example.com
    private final RoomRepository repo;
    private final SessionRegistry sessions;
    private final OfflineStore offline;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>(); // live cache

    public MucService(String serviceDomain, RoomRepository repo,
                      SessionRegistry sessions, OfflineStore offline) {
        this.serviceDomain = serviceDomain; this.repo = repo;
        this.sessions = sessions; this.offline = offline;
    }

    public String serviceDomain() { return serviceDomain; }

    /** Create a group. Address is a UUID; name is editable metadata. */
    public Room createRoom(String displayName, String ownerJid, RoomConfig base)
            throws MucException {
        String roomJid = UUID.randomUUID() + "@" + serviceDomain;
        RoomConfig cfg = base.withName(displayName);

        repo.saveRoom(new RoomRepository.RoomRecord(roomJid, displayName,
                cfg.description(), cfg.membersOnly(), cfg.persistent(),
                cfg.moderated(), cfg.nonAnonymous(), cfg.maxOccupants()));
        repo.upsertAffiliation(roomJid, bare(ownerJid), Affiliation.OWNER);

        Room room = new Room(roomJid, ownerJid, cfg, repo);
        rooms.put(roomJid, room);
        return room;
    }

    /** Get a live room, loading from DB if it's not currently in memory. */
    public Room room(String roomJid) {
        Room r = rooms.get(roomJid);
        if (r != null) return r;
        return repo.loadRoom(roomJid).map(rec -> {
            Room loaded = Room.fromRecord(rec, repo);
            rooms.putIfAbsent(roomJid, loaded);
            return rooms.get(roomJid);
        }).orElse(null);
    }

    /** Write each Outbound to its recipient's session (or stash if offline). */
    public void dispatch(List<Room.Outbound> outbound) {
        for (Room.Outbound o : outbound) {
            Session target = sessions.find(o.toJid);
            if (target != null) target.writeXML(o.xml);
            else                offline.save(o.toJid, o.xml);
        }
    }

    private static String bare(String jid) {
        int s = jid.indexOf('/'); return s < 0 ? jid : jid.substring(0, s);
    }
}