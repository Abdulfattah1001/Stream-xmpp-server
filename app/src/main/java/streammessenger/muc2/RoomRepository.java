package streammessenger.muc;

import com.example.xmpp.muc.Affiliation;
import java.util.Map;
import java.util.Optional;

/** Durable storage contract for rooms + memberships. Implement with JDBC etc. */
public interface RoomRepository {

    final class RoomRecord {
        public final String roomJid, name, description;
        public final boolean membersOnly, persistent, moderated, nonAnonymous;
        public final int maxOccupants;
        public RoomRecord(String roomJid, String name, String description,
                          boolean membersOnly, boolean persistent, boolean moderated,
                          boolean nonAnonymous, int maxOccupants) {
            this.roomJid = roomJid; this.name = name; this.description = description;
            this.membersOnly = membersOnly; this.persistent = persistent;
            this.moderated = moderated; this.nonAnonymous = nonAnonymous;
            this.maxOccupants = maxOccupants;
        }
    }

    void saveRoom(RoomRecord record);
    Optional<RoomRecord> loadRoom(String roomJid);

    void upsertAffiliation(String roomJid, String bareJid, Affiliation aff);
    void deleteAffiliation(String roomJid, String bareJid);
    Map<String, Affiliation> loadAffiliations(String roomJid);
}