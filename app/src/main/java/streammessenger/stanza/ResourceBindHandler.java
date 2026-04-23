package streammessenger.stanza;


import java.util.logging.Logger;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;

import streammessenger.db.DatabaseManager;
import streammessenger.mutlidevice.DeviceManager;
import streammessenger.session.Session;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.security.SecureRandom;
import java.util.List;

/**
 * Handles resource binding (RFC 6120 §7).
 *
 * Resource binding is the final step before a session is fully operational.
 * It happens after authentication, when the client sends:
 *
 *   <iq type='set' id='bind1'>
 *     <bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'>
 *       <resource>mobile</resource>   ← optional
 *     </bind>
 *   </iq>
 *
 * Server responds with the full JID:
 *
 *   <iq type='result' id='bind1'>
 *     <bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'>
 *       <jid>alice@domain.com/mobile</jid>
 *     </bind>
 *   </iq>
 *
 * After this the session is FULLY READY for stanza exchange.
 * We also deliver any pending offline messages and subscription requests.
 *
 * Stateless singleton.
 */
public final class ResourceBindHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(ResourceBindHandler.class.getName());

    private static final String BIND_NS = "urn:ietf:params:xml:ns:xmpp-bind";
    private static final int MAX_RESOURCE_LENGTH = 1023;
    private static final SecureRandom secureRandom = new SecureRandom();

    private final DatabaseManager db;

    public ResourceBindHandler(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public void handle(StartElement element, XMLEventReader reader, Session session) {
        if (!session.isAuthenticated()) {
            sendError(session, null, "not-authorized", "auth");
            consumeElement(reader);
            return;
        }

        String iqId = getAttr(element, "id");
        String iqType = getAttr(element, "type");

        if (!"set".equals(iqType)) {
            sendError(session, iqId, "bad-request", "modify");
            consumeElement(reader);
            return;
        }

        // Parse the requested resource from <bind><resource>name</resource></bind>
        String requestedResource = extractRequestedResource(reader);

        // Generate or sanitize resource
        String resource = (requestedResource == null || requestedResource.isBlank())
                ? generateResource()
                : sanitizeResource(requestedResource);

        if (resource == null) {
            // Sanitization failed - resource contained illegal characters
            sendError(session, iqId, "jid-malformed", "modify");
            return;
        }

        // Bind the resource to the session
        session.setResource(resource);
        String fullJid = session.getJid();

        // Confirm the binding
        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<bind xmlns='%s'>" +
                        "<jid>%s</jid>" +
                        "</bind></iq>",
                iqId != null ? escapeXml(iqId) : "",
                BIND_NS,
                escapeXml(fullJid)
        ));

        logger.info("Resource bound: " + fullJid + " uid=" + session.getUid());

        // Session is now fully operational
        // Deliver anything that was waiting for this user
        deliverPendingItems(session);
    }

    // Add to handleBind() after session.setResource(resource):

    // Register this device in the device registry
    private void registerDevice(Session session,
                                String resource,
                                DatabaseManager db,
                                DeviceManager deviceManager) {

        String userId = extractUserId(session.getContactId());

        // Client may have sent device info in the bind IQ
        // For now: use resource as device name
        String deviceName = resource;
        String platform   = guessPlatform(resource);

        DeviceManager.DeviceRecord device = deviceManager.registerDevice(
                userId,
                null,           // No client device ID yet - server generates
                deviceName,
                platform,
                null,           // App version - not available at bind time
                null            // Push token - provided separately
        );

        if (device != null) {
            session.setDeviceId(device.deviceId());
            logger.fine("Device registered on bind: deviceId="
                    + device.deviceId() + " uid=" + session.getUid());
        }
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private String guessPlatform(String resource) {
        if (resource == null) return "unknown";
        String lower = resource.toLowerCase();
        if (lower.contains("android")) return "android";
        if (lower.contains("ios") || lower.contains("iphone")
                || lower.contains("ipad")) return "ios";
        if (lower.contains("web")) return "web";
        if (lower.contains("desktop") || lower.contains("mac")
                || lower.contains("windows")) return "desktop";
        return "unknown";
    }

    // =========================================================================
    // Post-bind delivery
    // =========================================================================

    /**
     * After resource binding, deliver everything that was held for this user:
     *  1. Offline messages (stored while they were logged out)
     *  2. Pending subscription requests (friend requests received while offline)
     *
     * These are delivered in order: subscriptions first (so roster is up to date)
     * then messages (so they appear after the contact list is current).
     */
    private void deliverPendingItems(Session session) {
        String contactId = session.getContactId();

        // 1. Deliver pending subscription requests
        deliverPendingSubscriptions(session, contactId);

        // 2. Deliver offline messages
        deliverOfflineMessages(session, contactId);
    }

    /**
     * Delivers pending subscription requests (friend requests received while offline).
     *
     * Example: Bob sent Alice a friend request while Alice was offline.
     * When Alice logs in and binds, she receives Bob's subscribe request.
     */
    private void deliverPendingSubscriptions(Session session, String contactId) {
        List<DatabaseManager.PendingSubscription> pending =
                db.fetchPendingSubscriptions(contactId);

        if (pending.isEmpty()) return;

        logger.info("Delivering " + pending.size()
                + " pending subscriptions to " + contactId);

        for (DatabaseManager.PendingSubscription sub : pending) {
            String stanza = String.format(
                    "<presence type='%s' from='%s' to='%s'/>",
                    escapeXml(sub.type()),
                    escapeXml(sub.fromJid()),
                    escapeXml(contactId)
            );
            session.writeXML(stanza);
        }
    }

    /**
     * Delivers offline messages stored while the user was disconnected.
     *
     * Messages are delivered with XEP-0203 Delayed Delivery timestamps
     * so the client knows when they were originally sent.
     *
     * Example:
     *   <message from='bob@domain' to='alice@domain'>
     *     <body>Hello!</body>
     *     <delay xmlns='urn:xmpp:delay'
     *            from='domain.com'
     *            stamp='2024-01-15T10:30:00Z'/>
     *   </message>
     */
    private void deliverOfflineMessages(Session session, String contactId) {
        List<DatabaseManager.OfflineMessage> messages =
                db.fetchOfflineMessages(contactId);

        if (messages.isEmpty()) return;

        logger.info("Delivering " + messages.size()
                + " offline messages to " + contactId);

        for (DatabaseManager.OfflineMessage msg : messages) {
            // Format timestamp as XEP-0082 datetime string
            String timestamp = formatTimestamp(msg.createdAt());

            String stanza = String.format(
                    "<message id='%s' from='%s' to='%s' type='chat'>" +
                            "<body>%s</body>" +
                            "<delay xmlns='urn:xmpp:delay' from='%s' stamp='%s'/>" +
                            "</message>",
                    escapeXml(msg.stanzaId()),
                    escapeXml(msg.fromJid()),
                    escapeXml(contactId),
                    escapeXml(msg.body()),
                    escapeXml(extractDomain(contactId)),
                    timestamp
            );
            session.writeXML(stanza);
        }
    }

    // =========================================================================
    // Resource parsing and generation
    // =========================================================================

    /**
     * Extracts the resource name from the IQ body:
     *
     *   <iq type='set'>
     *     <bind xmlns='...'>
     *       <resource>HERE</resource>
     *     </bind>
     *   </iq>
     *
     * Returns null if client didn't specify a resource (server will generate one).
     */
    private String extractRequestedResource(XMLEventReader reader) {
        boolean inBind = false;
        boolean inResource = false;
        StringBuilder resource = new StringBuilder();

        try {
            int depth = 1; // We're already inside <iq>

            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    String name = event.asStartElement().getName().getLocalPart();
                    if ("bind".equals(name)) inBind = true;
                    if ("resource".equals(name) && inBind) inResource = true;
                }

                if (event.isCharacters() && inResource) {
                    resource.append(event.asCharacters().getData());
                }

                if (event.isEndElement()) {
                    depth--;
                    String name = event.asEndElement().getName().getLocalPart();
                    if ("resource".equals(name)) inResource = false;
                    if ("bind".equals(name)) inBind = false;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing bind IQ: " + e.getMessage());
        }

        String result = resource.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Sanitizes a client-provided resource name.
     *
     * Rules:
     *  - Max 1023 characters (RFC 7622)
     *  - No control characters
     *  - No XML-unsafe characters (will be escaped anyway but reject upfront)
     *  - No leading/trailing whitespace
     *
     * Returns null if the resource is invalid.
     */
    private String sanitizeResource(String resource) {
        if (resource == null) return null;

        resource = resource.trim();

        if (resource.isEmpty()) return null;
        if (resource.length() > MAX_RESOURCE_LENGTH) {
            resource = resource.substring(0, MAX_RESOURCE_LENGTH);
        }

        // Reject control characters
        for (char c : resource.toCharArray()) {
            if (Character.isISOControl(c)) return null;
        }

        return resource;
    }

    /**
     * Generates a random resource name when the client doesn't provide one.
     * Format: "res-<8 random hex chars>"
     * Example: "res-a3f2b1c4"
     */
    private String generateResource() {
        byte[] bytes = new byte[4];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("res-");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // =========================================================================
    // Error responses
    // =========================================================================

    /**
     * Sends an IQ error response.
     *
     * @param session   The session to write to
     * @param iqId      The IQ id to echo (can be null)
     * @param condition The XMPP stanza error condition
     * @param type      The error type: cancel|modify|auth|wait
     */
    private void sendError(Session session, String iqId,
                           String condition, String type) {
        session.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='%s'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
                type,
                condition
        ));
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /**
     * Formats a SQL Timestamp as an XEP-0082 / ISO 8601 datetime string.
     * Example: "2024-01-15T10:30:00.000Z"
     */
    private String formatTimestamp(java.sql.Timestamp ts) {
        if (ts == null) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") {{
            setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        }}.format(ts);
    }

    private String extractDomain(String jid) {
        if (jid == null) return "";
        int at = jid.indexOf('@');
        if (at == -1) return jid;
        int slash = jid.indexOf('/', at);
        return slash == -1 ? jid.substring(at + 1) : jid.substring(at + 1, slash);
    }

    private String getAttr(StartElement element, String name) {
        javax.xml.stream.events.Attribute attr =
                element.getAttributeByName(new QName(name));
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
                .replace(">", "&gt;").replace("'", "&apos;")
                .replace("\"", "&quot;");
    }
}