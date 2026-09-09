package streammessenger.roster;

import streammessenger.db.DatabaseManager;
import streammessenger.stanza.PrivacyHandler;

public class PrivacyEngine {
    private final DatabaseManager db;
    private final RosterManager roster;

    public PrivacyEngine(DatabaseManager db, RosterManager roster) {
        this.db = db;
        this.roster = roster;
    }

    /** Hard block check — used for messages, subscriptions, presence, everything. */
    public boolean isBlocked(String owner, String viewer) {
        return db.isBlocked(owner, viewer);
    }

    /** Field-level visibility check for profile data (last_seen/photo/about). */
    public boolean canView(String ownerJid, String viewerJid, String field) {
        if (ownerJid.equalsIgnoreCase(viewerJid)) return true;
        if (isBlocked(ownerJid, viewerJid)) return false;

        for (PrivacyHandler.PrivacyException ex : db.getPrivacyExceptions(ownerJid)) {
            if (ex.field().equals(field) && ex.jid().equalsIgnoreCase(viewerJid)) {
                return ex.type() == PrivacyHandler.PrivacyException.ExceptionType.ALLOW;
            }
        }

        PrivacyHandler.Privacy p = db.getPrivacy(ownerJid).orElse(PrivacyHandler.Privacy.defaults());
        String visibility = switch (field) {
            case "last_seen"     -> p.lastSeenVisibility();
            case "profile_photo" -> p.photoVisibility();
            case "about"         -> p.aboutVisibility();
            default -> "contacts";
        };

        return switch (visibility) {
            case "everyone" -> true;
            case "nobody"   -> false;
            // TODO: case "contacts" -> roster.areContacts(ownerJid, viewerJid); The rosters aren't populated now, it will be populated later
            default -> false;
        };
    }

    public boolean canSendReadReceipt(String ownerJid, String viewerJid) {
        if (isBlocked(ownerJid, viewerJid)) return false;
        return db.getPrivacy(ownerJid).map(PrivacyHandler.Privacy::readReceiptsEnabled).orElse(true);
    }
}