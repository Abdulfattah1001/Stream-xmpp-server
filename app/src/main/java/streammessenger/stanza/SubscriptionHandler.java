package streammessenger.stanza;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;

import streammessenger.db.DatabaseManager;
import streammessenger.roster.RosterItem;
import streammessenger.roster.RosterManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;
import java.util.Collections;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Handles presence subscription stanzas (RFC 6121 §3):
 * <p>
 *   subscribe     → "I want to see your presence" (friend request)
 *   subscribed    → "OK, you can see my presence" (accepted)
 *   unsubscribe   → "Stop sending me your presence"
 *   unsubscribed  → "I'm revoking your access to my presence"
 * <p>
 * These are the mechanics behind "friend requests" in any IM system.
 * <p>
 * Subscription state machine per RFC 6121 §3.1:
 * <p>
 *   none + send subscribe    → none/ask=subscribe (pending out)
 *   none/ask + recv subscribed → to (they approved us)
 *   recv subscribe           → from (they want our presence)
 *   recv subscribe + send subscribed → from (we approved them)
 * <p>
 * Stateless singleton - safe to share across all connections.
 */
public final class SubscriptionHandler implements StanzaHandler {

    private static final Logger logger = Logger.getLogger(SubscriptionHandler.class.getName());

    private final SessionRegistry registry;
    private final DatabaseManager db;
    private final RosterManager rosterManager;

    public SubscriptionHandler(SessionRegistry registry,
                               DatabaseManager db,
                               RosterManager rosterManager) {
        this.registry = registry;
        this.db = db;
        this.rosterManager = rosterManager;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {
        if (!session.isAuthenticated()) {
            consumeElement(reader);
            return;
        }

        String type = getAttr(element, "type");
        String to = getAttr(element, "to");

        if (type == null || to == null) {
            consumeElement(reader);
            return;
        }

        // Strip resource from 'to' - subscriptions are per bare JID
        String toContactId = bareJid(to);

        consumeElement(reader); // Consume rest of <presence> element

        switch (type) {
            case "subscribe"   -> handleSubscribeRequest(session, toContactId);
            case "subscribed"  -> handleSubscriptionApproval(session, toContactId);
            case "unsubscribe" -> handleUnsubscribe(session, toContactId);
            case "unsubscribed"-> handleUnsubscribed(session, toContactId);
            default -> logger.fine("Unknown presence type: " + type);
        }
    }

    // =========================================================================
    // Subscribe - "I want to see your presence"
    // =========================================================================

    /**
     * Alice sends <presence type='subscribe' to='bob@domain'/>
     * <p>
     * Actions:
     *  1. Add/update Alice's roster: bob → subscription=none, ask=subscribe
     *  2. If Bob is online: deliver <presence type='subscribe' from='alice'/>
     *  3. If Bob is offline: store in pending_subscriptions table
     *  4. Push roster update to all Alice's resources
     */
    private void handleSubscribeRequest(Session requester, String targetContactId) {
        String requesterContactId = requester.getContactId();

        logger.info("Subscribe request: " + requesterContactId
                + " → " + targetContactId);

        // 1. Update Alice's roster (requester's side)
        RosterItem existingItem = db.getRosterItem(requesterContactId, targetContactId);
        if (existingItem == null) {
            // First contact - create roster entry
            existingItem = RosterItem.newContact(targetContactId, null);
        }

        if (!existingItem.isPendingOutbound()) {
            // Only proceed if not already pending
            RosterItem updated = RosterItem.withAsk(existingItem, "subscribe");
            db.upsertRosterItem(requesterContactId, updated);

            // Push roster change to all Alice's resources
            rosterManager.pushRosterUpdateToAllResources(requesterContactId, updated);
        }

        // 2. Deliver the subscription request to Bob
        String subscribeStanza = String.format(
                "<presence type='subscribe' from='%s' to='%s'/>",
                escapeXml(requesterContactId),
                escapeXml(targetContactId)
        );

        Optional<Session> targetSession = registry.getByContactId(targetContactId);
        if (targetSession.isPresent()) {
            // Bob is online - deliver directly
            targetSession.get().writeXML(subscribeStanza);
        } else {
            // Bob is offline - store for delivery on next login
            db.storePendingSubscription(requesterContactId, targetContactId, "subscribe");
        }
    }

    // =========================================================================
    // Subscribed - "Yes, you can see my presence"
    // =========================================================================

    /**
     * Bob sends <presence type='subscribed' to='alice@domain'/>
     * <p>
     * Bob is approving Alice's subscription request.
     * <p>
     * Actions:
     *  1. Update Bob's roster: alice → subscription=from (alice can see Bob's presence)
     *  2. Update Alice's roster: bob → subscription=to (alice sees Bob's presence)
     *     If Alice had both directions: subscription=both
     *  3. Send <presence type='subscribed'> to Alice
     *  4. Send Bob's current presence to Alice (she can now see it)
     *  5. Delete pending subscription record
     *  6. Push roster updates to all resources of both parties
     */
    private void handleSubscriptionApproval(Session approver, String requesterContactId) {
        String approverContactId = approver.getContactId();

        logger.info("Subscription approved: " + approverContactId
                + " approved " + requesterContactId);

        // 1. Update Bob's roster (approver) - requester can now see Bob's presence
        RosterItem bobsItem = db.getRosterItem(approverContactId, requesterContactId);
        String newBobSubscription = computeNewSubscription(
                bobsItem != null ? bobsItem.subscription() : "none",
                "from" // Bob gains 'from' (Alice subscribed to Bob)
        );
        RosterItem updatedBobItem = bobsItem != null
                ? RosterItem.withSubscription(bobsItem, newBobSubscription)
                : new RosterItem(requesterContactId, null, newBobSubscription,
                null, Collections.emptyList());
        db.upsertRosterItem(approverContactId, updatedBobItem);

        // 2. Update Alice's roster (requester) - Alice gains 'to' subscription
        RosterItem alicesItem = db.getRosterItem(requesterContactId, approverContactId);
        String newAliceSubscription = computeNewSubscription(
                alicesItem != null ? alicesItem.subscription() : "none",
                "to" // Alice gains 'to' (subscribed to Bob)
        );
        // Also clear the ask=subscribe since it's been approved
        RosterItem updatedAliceItem = alicesItem != null
                ? new RosterItem(alicesItem.jid(), alicesItem.name(),
                newAliceSubscription, null, alicesItem.groups())
                : new RosterItem(approverContactId, null, newAliceSubscription,
                null, Collections.emptyList());
        db.upsertRosterItem(requesterContactId, updatedAliceItem);

        // 3. Notify Alice that her request was approved
        String approvedStanza = String.format(
                "<presence type='subscribed' from='%s' to='%s'/>",
                escapeXml(approverContactId),
                escapeXml(requesterContactId)
        );

        Optional<Session> requesterSession = registry.getByContactId(requesterContactId);
        if (requesterSession.isPresent()) {
            requesterSession.get().writeXML(approvedStanza);
        } else {
            db.storePendingSubscription(approverContactId, requesterContactId, "subscribed");
        }

        // 4. Send Bob's current presence to Alice so she sees his status immediately
        sendCurrentPresence(approver, requesterContactId);

        // 5. Clean up pending subscription record
        db.deletePendingSubscription(requesterContactId, approverContactId);

        // 6. Push roster updates to all resources of both parties
        rosterManager.pushRosterUpdateToAllResources(approverContactId, updatedBobItem);
        rosterManager.pushRosterUpdateToAllResources(requesterContactId, updatedAliceItem);
    }

    // =========================================================================
    // Unsubscribe - "Stop sending me your presence"
    // =========================================================================

    /**
     * Alice sends <presence type='unsubscribe' to='bob@domain'/>
     * <p>
     * Alice wants to stop receiving Bob's presence.
     * <p>
     * Actions:
     *  1. Update Alice's roster: remove 'to' from subscription
     *  2. Notify Bob that Alice unsubscribed
     *  3. Bob automatically sends <presence type='unavailable'> to Alice
     *  4. Push roster updates
     */
    private void handleUnsubscribe(Session requester, String targetContactId) {
        String requesterContactId = requester.getContactId();

        logger.info("Unsubscribe: " + requesterContactId + " → " + targetContactId);

        // Update Alice's roster - remove 'to' direction
        rosterManager.updateSubscriptionState(
                requesterContactId, targetContactId,
                removeDirection(db.getRosterItem(requesterContactId, targetContactId), "to")
        );

        // Notify Bob
        String unsubStanza = String.format(
                "<presence type='unsubscribe' from='%s' to='%s'/>",
                escapeXml(requesterContactId),
                escapeXml(targetContactId)
        );

        Optional<Session> targetSession = registry.getByContactId(targetContactId);
        if (targetSession.isPresent()) {
            targetSession.get().writeXML(unsubStanza);
            // Bob's client should auto-respond with <presence type='unsubscribed'/>
        } else {
            db.storePendingSubscription(requesterContactId, targetContactId, "unsubscribe");
        }
    }

    // =========================================================================
    // Unsubscribed - "I'm revoking your access to my presence"
    // =========================================================================

    /**
     * Bob sends <presence type='unsubscribed' to='alice@domain'/>
     * <p>
     * Bob is revoking Alice's subscription to his presence.
     * Could be a rejection of a subscribe request, or removing an existing one.
     * <p>
     * Actions:
     *  1. Update Bob's roster: remove 'from' direction
     *  2. Update Alice's roster: remove 'to' direction
     *  3. Send <presence type='unsubscribed'> to Alice
     *  4. Send <presence type='unavailable'> to Alice (she can't see Bob now)
     *  5. Push roster updates to both
     */
    private void handleUnsubscribed(Session revoker, String targetContactId) {
        String revokerContactId = revoker.getContactId();

        logger.info("Unsubscribed: " + revokerContactId
                + " revoked access for " + targetContactId);

        // Update Bob's roster - remove 'from' (Alice no longer subscribed)
        String newBobSub = removeDirection(
                db.getRosterItem(revokerContactId, targetContactId), "from");
        rosterManager.updateSubscriptionState(revokerContactId, targetContactId, newBobSub);

        // Update Alice's roster - remove 'to' (she can no longer see Bob)
        String newAliceSub = removeDirection(
                db.getRosterItem(targetContactId, revokerContactId), "to");
        rosterManager.updateSubscriptionState(targetContactId, revokerContactId, newAliceSub);

        // Notify Alice
        Optional<Session> targetSession = registry.getByContactId(targetContactId);
        if (targetSession.isPresent()) {
            targetSession.get().writeXML(String.format(
                    "<presence type='unsubscribed' from='%s' to='%s'/>",
                    escapeXml(revokerContactId),
                    escapeXml(targetContactId)
            ));
            // Also send unavailable so Alice's client removes Bob from presence
            targetSession.get().writeXML(String.format(
                    "<presence type='unavailable' from='%s'/>",
                    escapeXml(revokerContactId)
            ));
        } else {
            db.storePendingSubscription(revokerContactId, targetContactId, "unsubscribed");
        }

        // Delete any pending subscription request
        db.deletePendingSubscription(targetContactId, revokerContactId);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Computes the new subscription state after gaining a direction.
     * <p>
     * Current → gaining → result:
     *   none  + from → from
     *   none  + to   → to
     *   from  + to   → both
     *   to    + from → both
     *   both  + *    → both (no change)
     */
    private String computeNewSubscription(String current, String gaining) {
        if ("both".equals(current)) return "both";
        if (current.equals(gaining)) return current;
        if (("from".equals(current) && "to".equals(gaining)) ||
                ("to".equals(current) && "from".equals(gaining))) {
            return "both";
        }
        return gaining;
    }

    /**
     * Removes a direction from a subscription state.
     * <p>
     * both - from → to
     * both - to   → from
     * from - from → none
     * to   - to   → none
     */
    private String removeDirection(RosterItem item, String direction) {
        if (item == null) return "none";
        String current = item.subscription();

        return switch (current) {
            case "both" -> "from".equals(direction) ? "to" : "from";
            case "from" -> "from".equals(direction) ? "none" : "from";
            case "to"   -> "to".equals(direction)   ? "none" : "to";
            default     -> "none";
        };
    }

    /**
     * Sends Bob's current presence to a specific contact.
     * Called after subscription approval so Alice immediately sees Bob's status.
     */
    private void sendCurrentPresence(Session session, String toContactId) {
        Optional<Session> target = registry.getByContactId(toContactId);
        if (target.isEmpty()) return;

        // Build a basic available presence from this session
        // In a full implementation, session would store current show/status
        String presenceXml = String.format(
                "<presence from='%s' to='%s'/>",
                escapeXml(session.getJid()),
                escapeXml(toContactId)
        );
        target.get().writeXML(presenceXml);
    }

    private String bareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    private String getAttr(StartElement element, String name) {
        Attribute attr = element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private void consumeElement(XMLEventReader reader) {
        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent e = reader.nextEvent();
                if (e.isStartElement()) depth++;
                if (e.isEndElement()) depth--;
            }
        } catch (XMLStreamException ignored) {}
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}
