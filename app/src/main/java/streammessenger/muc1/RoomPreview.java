package streammessenger.muc1;

import java.util.Collections;
import java.util.List;

/**
 * Read-only snapshot of a room shown <b>before</b> a user joins — the
 * WhatsApp "you've been invited to <Group>" screen.
 *
 * <p>Crucially, building a preview has <b>no side effects</b>: it does not add
 * the viewer to the room, does not consume an invite link, and does not reveal
 * private member identities in an anonymous room.</p>
 */
public final class RoomPreview {

    /** A trimmed, privacy-safe view of a member for the preview avatar row. */
    public static final class MemberPreview {
        public final String displayName;   // nick or vCard name
        public final String avatarHint;    // e.g. photo hash / url — optional
        public MemberPreview(String displayName, String avatarHint) {
            this.displayName = displayName; this.avatarHint = avatarHint;
        }
    }

    public final String roomJid;
    public final String name;
    public final String description;
    public final int memberCount;        // durable members (affiliation>=member)
    public final int onlineCount;         // currently present occupants
    public final boolean membersOnly;
    public final boolean passwordProtected;
    public final String subject;
    private final List<MemberPreview> sampleMembers; // small sample for UI
    private final List<String> teaser;               // last few messages (optional)

    public RoomPreview(String roomJid, String name, String description,
                       int memberCount, int onlineCount, boolean membersOnly,
                       boolean passwordProtected, String subject,
                       List<MemberPreview> sampleMembers, List<String> teaser) {
        this.roomJid = roomJid; this.name = name; this.description = description;
        this.memberCount = memberCount; this.onlineCount = onlineCount;
        this.membersOnly = membersOnly; this.passwordProtected = passwordProtected;
        this.subject = subject;
        this.sampleMembers = sampleMembers; this.teaser = teaser;
    }

    public List<MemberPreview> sampleMembers() { return Collections.unmodifiableList(sampleMembers); }
    public List<String> teaser() { return Collections.unmodifiableList(teaser); }
}