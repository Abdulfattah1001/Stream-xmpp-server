package streammessenger.muc.service;


import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.muc.model.GroupRoom;
import streammessenger.muc.model.Occupant;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles XEP-0045 §7.2 presence broadcast.
 * <p>
 * Presence broadcasts happen on:
 *   - User joins room (§7.2.3)
 *   - User leaves room (§7.14)
 *   - Role/affiliation changes (§9, §10)
 *   - Nickname changes (§7.6)
 * <p>
 * IMPORTANT: presence stanzas in MUC have full room context:
 *   - from = roomjid/nick (NOT user's real JID)
 *   - includes <x xmlns='...muc#user'> with item info
 */
public final class PresenceBroadcaster {

    private static final Logger logger =
            Logger.getLogger(PresenceBroadcaster.class.getName());

    private static final String MUC_USER_NS =
            "http://jabber.org/protocol/muc#user";

    private final streammessenger.session.SessionRegistry sessionRegistry;
    private final DatabaseManager databaseManager;

    public PresenceBroadcaster(SessionRegistry sessionRegistry, DatabaseManager db) {
        this.sessionRegistry = sessionRegistry;
        this.databaseManager = db;
    }

    // =========================================================================
    // Join
    // =========================================================================

    /**
     * Broadcasts that an occupant joined the room.
     * <p>
     * Sends three things:
     *   1. To joining user: their own presence + presence of every other occupant
     *   2. To other occupants: presence of the joining user
     *   3. Room subject (XEP-0045 §7.2.15)
     */
    public void broadcastJoin(GroupRoom room, Occupant newOccupant) {
        logger.info("Broadcasting the join sequence:...");
        // 1. Send presence of all existing occupants to the new user
        Session newSession = sessionRegistry
                .getBySessionId(newOccupant.sessionUid())
                .orElse(null);

        if (newSession != null) {
            logger.info("The newSession is not null, broadcasting ...");
            for (Occupant existing : room.getOccupants()) {
                logger.info("Existing occupant is: "+existing.userJid());
                if (existing.userId().equals(newOccupant.userId())) continue;

                String existingPresence = buildPresence(
                        room, existing,
                        newOccupant.userJid(),
                        false
                );

                logger.info("XML Sent is: "+existingPresence);

                boolean sent = newSession.writeXML(existingPresence);

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

            sessionRegistry.getBySessionId(existing.sessionUid())
                    .ifPresent(s -> {
                        boolean sent = s.writeXML(presence);
                        if(!sent){
                            // Persist till the user resume back online
                            @SuppressWarnings("unused")
                            boolean persist = databaseManager.persistGroupPresence(newOccupant.roomJid(room.getJid()),
                                    newOccupant.userId(),newOccupant.affiliation().xmlValue(),
                                    newOccupant.userJid(),newOccupant.role().xmlValue(),false);
                        }
                    });
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
            sessionRegistry.getBySessionId(other.sessionUid())
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
            sessionRegistry.getBySessionId(other.sessionUid())
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