package streammessenger.muc.service;

import com.xmpp.muc.model.*;
import com.xmpp.session.Session;
import com.xmpp.session.SessionRegistry;

import java.util.logging.Logger;

/**
 * Handles XEP-0045 §7.2 presence broadcast.
 *
 * Presence broadcasts happen on:
 *   - User joins room (§7.2.3)
 *   - User leaves room (§7.14)
 *   - Role/affiliation changes (§9, §10)
 *   - Nickname changes (§7.6)
 *
 * IMPORTANT: presence stanzas in MUC have full room context:
 *   - from = roomjid/nick (NOT user's real JID)
 *   - includes <x xmlns='...muc#user'> with item info
 */
public final class PresenceBroadcaster {

    private static final Logger logger =
            Logger.getLogger(PresenceBroadcaster.class.getName());

    private static final String MUC_USER_NS =
            "http://jabber.org/protocol/muc#user";

    private final SessionRegistry sessionRegistry;

    public PresenceBroadcaster(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    // =========================================================================
    // Join
    // =========================================================================

    /**
     * Broadcasts that an occupant joined the room.
     *
     * Sends three things:
     *   1. To joining user: their own presence + presence of every other occupant
     *   2. To other occupants: presence of the joining user
     *   3. Room subject (XEP-0045 §7.2.15)
     */
    public void broadcastJoin(GroupRoom room, Occupant newOccupant) {

        // 1. Send presence of all existing occupants to the new user
        Session newSession = sessionRegistry
                .getByUid(newOccupant.sessionUid())
                .orElse(null);

        if (newSession != null) {
            for (Occupant existing : room.getOccupants()) {
                if (existing.userId().equals(newOccupant.userId())) continue;

                String existingPresence = buildPresence(
                        room, existing,
                        newOccupant.userJid(),
                        false
                );
                newSession.writeXML(existingPresence);
            }

            // Send own presence with status 110 (self)
            String selfPresence = buildPresence(
                    room, newOccupant,
                    newOccupant.userJid(),
                    true  // include status 110
            );
            newSession.writeXML(selfPresence);
        }

        // 2. Send new user's presence to everyone else
        for (Occupant existing : room.getOccupants()) {
            if (existing.userId().equals(newOccupant.userId())) continue;

            String presence = buildPresence(
                    room, newOccupant,
                    existing.userJid(),
                    false
            );

            sessionRegistry.getByUid(existing.sessionUid())
                    .ifPresent(s -> s.writeXML(presence));
        }

        logger.fine("Join broadcast: " + newOccupant.userId()
                + " to " + room.getOccupantCount() + " occupants");
    }

    // =========================================================================
    // Leave
    // =========================================================================

    public void broadcastLeave(GroupRoom room, Occupant occupant,
                                String reason, String reasonText) {
        String stanza = String.format(
            "<presence from='%s' to='%%s' type='unavailable'>" +
            "<x xmlns='%s'>" +
            "<item affiliation='%s' jid='%s' role='none'>" +
            "%s" +
            "</item>" +
            "</x></presence>",
            escapeXml(occupant.roomJid(room.getJid())),
            MUC_USER_NS,
            occupant.affiliation().xmlValue(),
            escapeXml(occupant.userJid()),
            reasonText != null
                ? "<reason>" + escapeXml(reasonText) + "</reason>"
                : ""
        );

        for (Occupant other : room.getOccupants()) {
            String personalized = String.format(stanza, other.userJid());
            sessionRegistry.getByUid(other.sessionUid())
                    .ifPresent(s -> s.writeXML(personalized));
        }
    }

    // =========================================================================
    // Affiliation change
    // =========================================================================

    public void broadcastAffiliationChange(GroupRoom room, Occupant occupant) {
        String stanza = String.format(
            "<presence from='%s' to='%%s'>" +
            "<x xmlns='%s'>" +
            "<item affiliation='%s' jid='%s' role='%s'/>" +
            "</x></presence>",
            escapeXml(occupant.roomJid(room.getJid())),
            MUC_USER_NS,
            occupant.affiliation().xmlValue(),
            escapeXml(occupant.userJid()),
            occupant.role().xmlValue()
        );

        for (Occupant other : room.getOccupants()) {
            String personalized = String.format(stanza, other.userJid());
            sessionRegistry.getByUid(other.sessionUid())
                    .ifPresent(s -> s.writeXML(personalized));
        }
    }

    // =========================================================================
    // Presence builder
    // =========================================================================

    private String buildPresence(GroupRoom room, Occupant occupant,
                                  String toJid, boolean includeSelfStatus) {
        return String.format(
            "<presence from='%s' to='%s'>" +
            "<x xmlns='%s'>" +
            "<item affiliation='%s' jid='%s' role='%s'/>" +
            "%s" +
            "</x></presence>",
            escapeXml(occupant.roomJid(room.getJid())),
            escapeXml(toJid),
            MUC_USER_NS,
            occupant.affiliation().xmlValue(),
            escapeXml(occupant.userJid()),
            occupant.role().xmlValue(),
            includeSelfStatus ? "<status code='110'/>" : ""
        );
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}