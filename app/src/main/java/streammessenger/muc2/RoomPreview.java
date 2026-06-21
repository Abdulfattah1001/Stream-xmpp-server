package streammessenger.muc;

import java.util.Collections;
import java.util.List;

/** Read-only snapshot shown BEFORE joining (no side effects when built). */
public final class RoomPreview {
    public final String roomJid;
    public final String name;
    public final String description;
    public final int memberCount;
    public final int onlineCount;
    public final boolean membersOnly;
    private final List<String> sampleMemberNicks;

    public RoomPreview(String roomJid, String name, String description,
                       int memberCount, int onlineCount, boolean membersOnly,
                       List<String> sampleMemberNicks) {
        this.roomJid = roomJid; this.name = name; this.description = description;
        this.memberCount = memberCount; this.onlineCount = onlineCount;
        this.membersOnly = membersOnly; this.sampleMemberNicks = sampleMemberNicks;
    }
    public List<String> sampleMemberNicks() { return Collections.unmodifiableList(sampleMemberNicks); }
}