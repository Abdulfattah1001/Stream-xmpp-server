package streammessenger.muc;

import com.example.xmpp.muc.Affiliation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory repo for tests / single-node. Swap for JdbcRoomRepository in prod. */
public final class InMemoryRoomRepository implements RoomRepository {
    private final Map<String, RoomRecord> rooms = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Affiliation>> affs = new ConcurrentHashMap<>();

    @Override public void saveRoom(RoomRecord r) { rooms.put(r.roomJid, r); }
    @Override public Optional<RoomRecord> loadRoom(String jid) { return Optional.ofNullable(rooms.get(jid)); }
    @Override public void upsertAffiliation(String room, String jid, Affiliation a) {
        affs.computeIfAbsent(room, k -> new ConcurrentHashMap<>()).put(jid, a);
    }
    @Override public void deleteAffiliation(String room, String jid) {
        Map<String, Affiliation> m = affs.get(room); if (m != null) m.remove(jid);
    }
    @Override public Map<String, Affiliation> loadAffiliations(String room) {
        return new HashMap<>(affs.getOrDefault(room, Map.of()));
    }
}