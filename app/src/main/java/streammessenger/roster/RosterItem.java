package streammessenger.roster;

import java.util.Collections;
import java.util.List;

/**
 * Represents one entry in a user's contact list (roster).
 *
 * subscription values (RFC 6121 §2.1):
 *   none    → No subscription in either direction
 *   from    → Contact receives OUR presence (they subscribed to us)
 *   to      → WE receive their presence (we subscribed to them)
 *   both    → Bidirectional subscription
 *   remove  → Special: signals this item should be deleted
 *
 * ask field:
 *   "subscribe" → We have sent a subscription request, awaiting approval
 *   null        → No pending outbound request
 */
public record RosterItem(
        String jid,           // Bare JID of the contact: friend@domain.com
        String name,          // Display name (user-defined label)
        String subscription,  // none | from | to | both | remove
        String ask,           // "subscribe" if pending outbound request, else null
        List<String> groups   // Organizational groups (e.g. "Family", "Work")
) {
    // Compact canonical constructor - defensive copy of groups list
    public RosterItem {
        if (jid == null || jid.isBlank()) throw new IllegalArgumentException("JID required");
        subscription = subscription != null ? subscription : "none";
        groups = groups != null ? Collections.unmodifiableList(groups) : Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static RosterItem newContact(String jid, String name) {
        return new RosterItem(jid, name, "none", null, Collections.emptyList());
    }

    public static RosterItem withSubscription(RosterItem base, String subscription) {
        return new RosterItem(
            base.jid(), base.name(), subscription, base.ask(), base.groups());
    }

    public static RosterItem withAsk(RosterItem base, String ask) {
        return new RosterItem(
            base.jid(), base.name(), base.subscription(), ask, base.groups());
    }

    public static RosterItem remove(String jid) {
        return new RosterItem(jid, null, "remove", null, Collections.emptyList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public boolean hasOutboundSubscription() {
        return "to".equals(subscription) || "both".equals(subscription);
    }

    public boolean hasInboundSubscription() {
        return "from".equals(subscription) || "both".equals(subscription);
    }

    public boolean isPendingOutbound() {
        return "subscribe".equals(ask);
    }

    /**
     * Builds the XML <item> element for inclusion in roster IQ responses.
     */
    public String toXml() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<item jid='%s'", escapeXml(jid)));

        if (name != null && !name.isBlank()) {
            sb.append(String.format(" name='%s'", escapeXml(name)));
        }

        sb.append(String.format(" subscription='%s'", subscription));

        if (ask != null) {
            sb.append(String.format(" ask='%s'", ask));
        }

        if (groups.isEmpty()) {
            sb.append("/>");
        } else {
            sb.append(">");
            for (String group : groups) {
                sb.append("<group>").append(escapeXml(group)).append("</group>");
            }
            sb.append("</item>");
        }

        return sb.toString();
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}