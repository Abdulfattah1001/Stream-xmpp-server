package streammessenger.roster;

import streammessenger.db.DatabaseManager;
import  streammessenger.session.Session;
import  streammessenger.session.SessionRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages all roster (contact list) operations.
 * <p>
 * Roster operations always involve two things:
 *  1. Persisting the change to the database
 *  2. Pushing the change to all active resources of the affected user(s)
 * <p>
 * RFC 6121 §2 defines the full roster management protocol.
 */
public final class RosterManager {

    private static final Logger logger = Logger.getLogger(RosterManager.class.getName());
    private static final String ROSTER_NS = "jabber:iq:roster";

    private final DatabaseManager db;
    private final SessionRegistry registry;

    public RosterManager(DatabaseManager db, SessionRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    // =========================================================================
    // Roster Get
    // =========================================================================

    /**
     * Handles: <iq type='get'><query xmlns='jabber:iq:roster'/></iq>
     * <p>
     * Returns the user's complete contact list.
     * Supports roster versioning: if client sends ver='X' and we agree,
     * we can send an empty result meaning "your cached roster is current".
     * <p>
     * @param contactId The requesting user's bare JID
     * @param iqId      The IQ stanza ID (must be echoed in response)
     * @param clientVer The roster version the client has cached (may be null)
     * @param session   The requesting session
     */
    public void handleRosterGet(String contactId, String iqId,
                                 String clientVer, Session session) {
        List<RosterItem> items = db.getRosterItems(contactId);
        String currentVer = computeRosterVersion(items);

        // If client's cached version matches, send empty result (no transfer needed)
        if (currentVer.equals(clientVer)) {
            session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                "<query xmlns='%s' ver='%s'/>" +
                "</iq>",
                iqId, ROSTER_NS, currentVer
            ));
            return;
        }

        // Send full roster
        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<query xmlns='%s' ver='%s'>",
            iqId, ROSTER_NS, currentVer
        ));

        for (RosterItem item : items) {
            xml.append(item.toXml());
        }

        xml.append("</query></iq>");
        session.writeXML(xml.toString());

        logger.info("Roster sent to " + contactId
                + " items=" + items.size() + " ver=" + currentVer);
    }

    // =========================================================================
    // Roster Set
    // =========================================================================

    /**
     * Handles: <iq type='set'>
     *            <query xmlns='jabber:iq:roster'>
     *              <item jid='friend@domain' name='Friend'/>
     *            </query>
     *          </iq>
     * <p>
     * Adds, updates, or removes a contact.
     * After DB update, pushes the change to ALL of the user's active resources.
     */
    public void handleRosterSet(RosterItem item, String iqId, Session session) {
        String ownerContactId = session.getContactId();
        if ("remove".equals(item.subscription())) {
            db.deleteRosterItem(ownerContactId, item.jid());
            logger.info("Roster item removed: " + ownerContactId + " -> " + item.jid());
        } else {
            db.upsertRosterItem(ownerContactId, item);
            logger.info("Roster item upserted: " + ownerContactId + " -> " + item.jid());
        }

        // RFC 6121 §2.1.6: Server MUST send roster push to all connected resources
        //pushRosterUpdateToAllResources(ownerContactId, item);

        // Acknowledge the set request
        //session.writeXML(String.format("<iq type='result' id='%s'/>", iqId));
    }

    // =========================================================================
    // Roster Push
    // =========================================================================

    /**
     * Pushes a roster update to all of a user's currently connected sessions.
     * <p>
     * This is called when:
     *  - The user modifies their own roster
     *  - A subscription state changes (contact approved/denied friend request)
     * <p>
     * The 'from' attribute is intentionally absent per RFC 6121 §2.1.6.
     */
    public void pushRosterUpdateToAllResources(String contactId, RosterItem item) {
        List<Session> sessions = registry.getSessionsByContactId(contactId);

        if (sessions.isEmpty()) return;

        String pushXml = String.format(
            "<iq type='set'>" +
            "<query xmlns='%s'>" +
            "%s" +
            "</query></iq>",
            ROSTER_NS, item.toXml()
        );

        for (Session s : sessions) {
            boolean sent = s.writeXML(pushXml);
            logger.fine("Roster push to " + s.getJid() + " sent=" + sent);
        }
    }


    public Set<String> contactsOf(String uid) {
        return Collections.emptySet();
    }
    // ==========================================================/===============
    // Subscription state management
    // Called by SubscriptionHandler when subscription state changes
    // =========================================================================

    /**
     * Updates the subscription field of a roster item.
     * Called when a subscription request is approved, denied, or cancelled.
     *
     * @param ownerContactId The roster owner
     * @param contactJid     The contact whose subscription state changed
     * @param newSubscription The new subscription value (from|to|both|none)
     */
    public void updateSubscriptionState(String ownerContactId,
                                         String contactJid,
                                         String newSubscription) {
        RosterItem existing = db.getRosterItem(ownerContactId, contactJid);

        RosterItem updated;
        if (existing != null) {
            updated = RosterItem.withSubscription(existing, newSubscription);
        } else {
            // Contact not in roster yet - create minimal entry
            updated = new RosterItem(contactJid, null, newSubscription,
                    null, java.util.Collections.emptyList());
        }

        db.upsertRosterItem(ownerContactId, updated);
        pushRosterUpdateToAllResources(ownerContactId, updated);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Computes a version token for the roster.
     * If the roster hasn't changed, the token is the same.
     * Clients cache by this token to avoid re-fetching unchanged rosters.
     * <p>
     * Implementation: SHA-1 hash of all JIDs + subscription states,
     * encoded as hex. Not cryptographically sensitive - just a change detector.
     */
    private String computeRosterVersion(List<RosterItem> items) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (RosterItem item : items) {
                sha1.update(item.jid().getBytes(StandardCharsets.UTF_8));
                sha1.update(item.subscription().getBytes(StandardCharsets.UTF_8));
                if (item.ask() != null) {
                    sha1.update(item.ask().getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] digest = sha1.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is always available in Java
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}