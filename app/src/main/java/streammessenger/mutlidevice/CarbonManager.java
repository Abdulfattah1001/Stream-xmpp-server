package streammessenger.mutlidevice;


import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Manages Message Carbons (XEP-0280).
 *
 * WHAT THIS DOES:
 * ────────────────
 * When Alice has multiple devices logged in (phone, laptop, tablet),
 * this manager ensures every message Alice sends OR receives is
 * copied to ALL her other devices so they stay in sync.
 *
 * TWO TYPES OF CARBONS:
 * ─────────────────────
 * 1. RECEIVED carbons:
 *    Bob sends to Alice → phone receives original
 *    → laptop gets a <received> carbon copy
 *    → tablet gets a <received> carbon copy
 *
 * 2. SENT carbons:
 *    Alice sends from phone → Bob receives original
 *    → Alice's laptop gets a <sent> carbon copy
 *    → Alice's tablet gets a <sent> carbon copy
 *
 * CARBON FORMAT:
 * ──────────────
 *   <message from='alice@domain/laptop' to='alice@domain/laptop'>
 *     <received xmlns='urn:xmpp:carbons:2'>
 *       <forwarded xmlns='urn:xmpp:forward:0'>
 *         <delay xmlns='urn:xmpp:delay' stamp='2024-01-15T10:30:00Z'/>
 *         <message from='bob@domain' to='alice@domain/phone'>
 *           <encrypted ...>CIPHERTEXT</encrypted>
 *         </message>
 *       </forwarded>
 *     </received>
 *   </message>
 *
 * CARBON ENABLE/DISABLE:
 * ──────────────────────
 * Each resource explicitly enables carbons after binding:
 *   <iq type='set' id='enable1'>
 *     <enable xmlns='urn:xmpp:carbons:2'/>
 *   </iq>
 *
 * WHY OPT-IN:
 *   Some clients (bots, headless clients) don't want carbons.
 *   Explicit opt-in prevents unnecessary traffic.
 *
 * DEDUPLICATION:
 * ──────────────
 * If a resource disconnects and reconnects while a carbon is in-flight,
 * we track delivery to prevent sending the same carbon twice.
 * Uses carbon_deliveries table with UNIQUE constraint.
 */
public final class CarbonManager {

    private static final Logger logger =
            Logger.getLogger(CarbonManager.class.getName());

    private static final String CARBONS_NS = "urn:xmpp:carbons:2";
    private static final String FORWARD_NS = "urn:xmpp:forward:0";
    private static final String DELAY_NS   = "urn:xmpp:delay";

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    // In-memory cache: contactId → Set of UIDs that have carbons enabled
    // Avoids DB lookups on every message send
    // Updated when enable/disable is called
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>
            carbonEnabled = new ConcurrentHashMap<>();

    private static final CarbonManager INSTANCE_HOLDER =
            new CarbonManager(null, null);

    private static CarbonManager instance;

    private CarbonManager(ConnectionPool pool, SessionRegistry registry) {
        this.pool     = pool;
        this.registry = registry;
    }

    public static void initialize(ConnectionPool pool,
                                   SessionRegistry registry) {
        instance = new CarbonManager(pool, registry);
    }

    public static CarbonManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                "CarbonManager not initialized");
        }
        return instance;
    }

    // =========================================================================
    // Enable / Disable
    // =========================================================================

    /**
     * Enables message carbons for a session.
     * Called when the client sends <enable xmlns='urn:xmpp:carbons:2'/>.
     *
     * Also persists to DB so if the server restarts while the session
     * is still connected, we can restore the state.
     */
    public void enableCarbons(Session session) {
        String contactId = session.getContactId();
        String uid       = session.getSessionId();

        // Update in-memory cache
        carbonEnabled
                .computeIfAbsent(contactId,
                        k -> new ConcurrentHashMap<>())
                .put(uid, Boolean.TRUE);

        // Persist to DB
        updateCarbonState(uid, true);

        logger.info("Carbons enabled: uid=" + uid
                + " contactId=" + contactId);
    }

    /**
     * Disables message carbons for a session.
     * Called when client sends <disable xmlns='urn:xmpp:carbons:2'/>.
     */
    public void disableCarbons(Session session) {
        String contactId = session.getContactId();
        String uid       = session.getSessionId();

        ConcurrentHashMap<String, Boolean> userCarbons =
                carbonEnabled.get(contactId);
        if (userCarbons != null) {
            userCarbons.remove(uid);
        }

        updateCarbonState(uid, false);

        logger.info("Carbons disabled: uid=" + uid);
    }

    /**
     * Called when a session disconnects.
     * Removes from carbon cache but keeps DB record for audit.
     */
    public void onSessionDisconnected(Session session) {
        String contactId = session.getContactId();
        if (contactId == null) return;

        ConcurrentHashMap<String, Boolean> userCarbons =
                carbonEnabled.get(contactId);
        if (userCarbons != null) {
            userCarbons.remove(session.getSessionId());
            if (userCarbons.isEmpty()) {
                carbonEnabled.remove(contactId);
            }
        }
    }

    /**
     * Returns true if this specific session has carbons enabled.
     */
    public boolean hasCarbonsEnabled(Session session) {
        ConcurrentHashMap<String, Boolean> userCarbons =
                carbonEnabled.get(session.getContactId());
        return userCarbons != null
                && Boolean.TRUE.equals(userCarbons.get(session.getSessionId()));
    }

    // =========================================================================
    // Carbon Delivery - RECEIVED type
    // =========================================================================

    /**
     * Called when a message is delivered to a user.
     * Copies the message to all OTHER carbon-enabled sessions of the same user.
     *
     * Example: Bob sends to Alice.
     *   originalRecipientUid = Alice's phone uid
     *   The message was delivered to Alice/phone.
     *   This method copies it to Alice/laptop and Alice/tablet.
     *
     * @param message              The full message stanza XML
     * @param recipientContactId   Bare JID of the message recipient (Alice)
     * @param originalRecipientUid The UID of the session that got the original
     * @param messageId            For deduplication
     * @param timestamp            ISO-8601 timestamp for the <delay> element
     */
    public void fanoutReceivedCarbon(String message,
                                      String recipientContactId,
                                      String originalRecipientUid,
                                      String messageId,
                                      String timestamp) {

        List<Session> otherSessions =
                getOtherCarbonSessions(recipientContactId, originalRecipientUid);

        if (otherSessions.isEmpty()) return;

        // Build the <received> carbon wrapper
        String carbonStanza = wrapAsReceived(
                message, recipientContactId, timestamp);

        for (Session session : otherSessions) {
            if (deliverCarbon(session, carbonStanza, messageId)) {
                logger.fine("Received carbon delivered to uid="
                        + session.getSessionId()
                        + " for messageId=" + messageId);
            }
        }
    }

    // =========================================================================
    // Carbon Delivery - SENT type
    // =========================================================================

    /**
     * Called when a user sends a message from one device.
     * Copies the sent message to all OTHER carbon-enabled sessions.
     *
     * Example: Alice sends from phone.
     *   senderUid = Alice's phone uid
     *   This copies the sent message to Alice/laptop and Alice/tablet
     *   so they show it in the chat history immediately.
     *
     * @param message         The full message stanza XML
     * @param senderContactId Alice's bare JID
     * @param senderUid       Alice's phone session uid (exclude this one)
     * @param messageId       For deduplication
     * @param timestamp       ISO-8601 timestamp
     */
    public void fanoutSentCarbon(String message,
                                  String senderContactId,
                                  String senderUid,
                                  String messageId,
                                  String timestamp) {

        List<Session> otherSessions =
                getOtherCarbonSessions(senderContactId, senderUid);

        if (otherSessions.isEmpty()) return;

        // Build the <sent> carbon wrapper
        String carbonStanza = wrapAsSent(
                message, senderContactId, timestamp);

        for (Session session : otherSessions) {
            if (deliverCarbon(session, carbonStanza, messageId)) {
                logger.fine("Sent carbon delivered to uid="
                        + session.getSessionId()
                        + " for messageId=" + messageId);
            }
        }
    }

    // =========================================================================
    // Carbon XML builders
    // =========================================================================

    /**
     * Wraps a message stanza as a RECEIVED carbon.
     *
     * Format:
     *   <message to='alice@domain/laptop'>
     *     <received xmlns='urn:xmpp:carbons:2'>
     *       <forwarded xmlns='urn:xmpp:forward:0'>
     *         <delay xmlns='urn:xmpp:delay' stamp='...'/>
     *         ORIGINAL_MESSAGE
     *       </forwarded>
     *     </received>
     *   </message>
     */
    private String wrapAsReceived(String originalMessage,
                                   String recipientContactId,
                                   String timestamp) {
        return String.format(
            "<message to='%s' xmlns='jabber:client'>" +
            "<received xmlns='%s'>" +
            "<forwarded xmlns='%s'>" +
            "<delay xmlns='%s' stamp='%s'/>" +
            "%s" +
            "</forwarded>" +
            "</received>" +
            "</message>",
            escapeXml(recipientContactId),
            CARBONS_NS,
            FORWARD_NS,
            DELAY_NS, timestamp,
            originalMessage
        );
    }

    /**
     * Wraps a message stanza as a SENT carbon.
     *
     * Format:
     *   <message to='alice@domain/laptop'>
     *     <sent xmlns='urn:xmpp:carbons:2'>
     *       <forwarded xmlns='urn:xmpp:forward:0'>
     *         <delay xmlns='urn:xmpp:delay' stamp='...'/>
     *         ORIGINAL_MESSAGE
     *       </forwarded>
     *     </sent>
     *   </message>
     */
    private String wrapAsSent(String originalMessage,
                               String senderContactId,
                               String timestamp) {
        return String.format(
            "<message to='%s' xmlns='jabber:client'>" +
            "<sent xmlns='%s'>" +
            "<forwarded xmlns='%s'>" +
            "<delay xmlns='%s' stamp='%s'/>" +
            "%s" +
            "</forwarded>" +
            "</sent>" +
            "</message>",
            escapeXml(senderContactId),
            CARBONS_NS,
            FORWARD_NS,
            DELAY_NS, timestamp,
            originalMessage
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns all carbon-enabled sessions for a user EXCEPT the specified uid.
     */
    private List<Session> getOtherCarbonSessions(String contactId,
                                                   String excludeUid) {
        List<Session> result = new ArrayList<>();

        ConcurrentHashMap<String, Boolean> userCarbons =
                carbonEnabled.get(contactId);
        if (userCarbons == null) return result;

        for (String uid : userCarbons.keySet()) {
            if (uid.equals(excludeUid)) continue;

            registry.getByUid(uid).ifPresent(session -> {
                if (session.isAuthenticated()) {
                    result.add(session);
                }
            });
        }

        return result;
    }

    /**
     * Delivers a carbon to a session with deduplication.
     *
     * @return true if delivered, false if duplicate or write failed
     */
    private boolean deliverCarbon(Session session,
                                   String carbonStanza,
                                   String messageId) {
        // Record delivery (unique constraint prevents duplicates)
        if (!recordCarbonDelivery(messageId, session.getSessionId())) {
            return false; // Already delivered to this resource
        }

        return session.writeXML(carbonStanza);
    }

    private boolean recordCarbonDelivery(String messageId, String sessionUid) {
        String sql = """
            INSERT INTO carbon_deliveries (message_id, resource_uid, delivered_at)
            VALUES (?::uuid, ?, NOW())
            ON CONFLICT (message_id, resource_uid) DO NOTHING
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, messageId);
            stmt.setString(2, sessionUid);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0; // 0 = duplicate (already delivered)

        } catch (SQLException e) {
            logger.warning("recordCarbonDelivery error: " + e.getMessage());
            return false;
        }
    }

    private void updateCarbonState(String sessionUid, boolean enabled) {
        String sql = """
            UPDATE active_sessions
            SET carbons_enabled   = ?,
                carbon_enabled_at = CASE WHEN ? THEN NOW() ELSE NULL END
            WHERE uid = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, enabled);
            stmt.setBoolean(2, enabled);
            stmt.setString(3, sessionUid);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updateCarbonState error: " + e.getMessage());
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}