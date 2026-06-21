package streammessenger.muc1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed representation of an {@code <x xmlns='http://jabber.org/protocol/muc#user'/>}
 * extension element.
 *
 * <p>This is the workhorse payload that the server attaches to presence
 * stanzas to tell occupants about each other's role/affiliation, and that
 * carries status codes (kicked, banned, self-presence, ...).</p>
 *
 * <p>It is an immutable-ish value object: build it, then serialize. We keep
 * the lists mutable internally only during construction.</p>
 */
public final class MucUserExtension {

    /** The <item/> describing affiliation/role and optionally the real JID. */
    public static final class Item {
        public Affiliation affiliation = Affiliation.NONE;
        public Role role = Role.NONE;
        /** Real bare JID of the occupant (only revealed in non-anon rooms / to admins). */
        public String jid;
        /** Nickname. */
        public String nick;
        /** Optional human-readable reason (e.g. kick reason). */
        public String reason;
        /** For kick/ban: who performed the action. */
        public String actorJid;
    }

    private Item item;
    private final List<MucStatus> statuses = new ArrayList<>();

    public MucUserExtension withItem(Item i) { this.item = i; return this; }
    public MucUserExtension addStatus(MucStatus s) { statuses.add(s); return this; }

    public Item item() { return item; }
    public List<MucStatus> statuses() { return Collections.unmodifiableList(statuses); }
    public boolean hasStatus(MucStatus s) { return statuses.contains(s); }
}