package streammessenger.vhost;


import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Manages virtual hosting - multiple XMPP domains on one server.
 *
 * Routing logic:
 *   Stanza arrives with to='user@domain.com'
 *   1. Extract domain from JID
 *   2. isLocalDomain(domain)? → route locally
 *   3. Not local → would go to S2S federation (not implemented yet)
 *   4. Unknown domain → return <remote-server-not-found> error
 *
 * Domain registration:
 *   Domains are registered at startup from config.
 *   Can also be added/removed at runtime for dynamic vhost management.
 *
 * Thread safety: ConcurrentHashMap for domain registry.
 */
public final class VirtualHostManager {

    private static final Logger logger = Logger.getLogger(VirtualHostManager.class.getName());

    // domain name (lowercase) → its configuration
    private final Map<String, DomainConfig> domains = new ConcurrentHashMap<>();

    private final SessionRegistry registry;

    // In the future this would be an S2SConnectionManager
    // For now: log that federation is not supported
    private static final String S2S_NOT_IMPLEMENTED =
            "Server-to-server federation not yet implemented";

    public VirtualHostManager(SessionRegistry registry) {
        this.registry = registry;
    }

    // =========================================================================
    // Domain registration
    // =========================================================================

    /**
     * Registers a domain this server is authoritative for.
     * Called at startup for each domain in config.
     */
    public void registerDomain(String domain, DomainConfig config) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("Domain cannot be null/empty");
        }
        String normalized = domain.toLowerCase().trim();
        domains.put(normalized, config);
        logger.info("Domain registered: " + normalized + " " + config);
    }

    /**
     * Removes a domain from the server.
     * In-flight sessions for this domain are not immediately terminated
     * but no new authentications will be accepted.
     */
    public void unregisterDomain(String domain) {
        DomainConfig removed = domains.remove(domain.toLowerCase());
        if (removed != null) {
            logger.info("Domain unregistered: " + domain);
        }
    }

    // =========================================================================
    // Domain queries
    // =========================================================================

    public boolean isLocalDomain(String domain) {
        if (domain == null) return false;
        return domains.containsKey(domain.toLowerCase());
    }

    public Optional<DomainConfig> getDomainConfig(String domain) {
        if (domain == null) return Optional.empty();
        return Optional.ofNullable(domains.get(domain.toLowerCase()));
    }

    public Collection<String> getLocalDomains() {
        return Collections.unmodifiableSet(domains.keySet());
    }

    public boolean isRegistrationOpen(String domain) {
        return getDomainConfig(domain)
                .map(DomainConfig::isRegistrationOpen)
                .orElse(false);
    }

    public boolean isFederationEnabled(String domain) {
        return getDomainConfig(domain)
                .map(DomainConfig::isFederationEnabled)
                .orElse(false);
    }

    // =========================================================================
    // Stanza routing
    // =========================================================================

    /**
     * Routes a stanza to the correct domain handler.
     *
     * Called by XMPPStreamProcessor after determining the 'to' domain.
     *
     * Routing table:
     *   Local domain    → routeLocally()
     *   Remote domain   → routeToFederation() (S2S - future)
     *   Unknown domain  → return error to sender
     *
     * @param toDomain  The domain part of the 'to' JID
     * @param stanza    The full XML stanza string
     * @param sender    The session that sent the stanza (for error replies)
     * @return true if routed successfully
     */
    public boolean route(String toDomain, String stanza, Session sender) {
        if (toDomain == null || toDomain.isBlank()) {
            sendDomainError(sender, "improper-addressing",
                    "Missing or empty 'to' domain");
            return false;
        }

        String normalized = toDomain.toLowerCase().trim();

        if (isLocalDomain(normalized)) {
            return routeLocally(normalized, stanza, sender);
        }

        if (isFederationEnabled(normalized)) {
            return routeToFederation(normalized, stanza, sender);
        }

        // Unknown domain - send error back to sender
        logger.warning("Stanza to unknown domain: " + normalized
                + " from=" + sender.getContactId());
        sendDomainError(sender, "remote-server-not-found",
                "Domain not found: " + normalized);
        return false;
    }

    /**
     * Routes a stanza to a local user.
     *
     * Extracts the recipient JID from the stanza and finds their session.
     * This is the common path for messages between users on the same server.
     */
    private boolean routeLocally(String domain, String stanza, Session sender) {
        // Extract 'to' attribute from the stanza XML
        String toJid = extractToJid(stanza);
        if (toJid == null) {
            sendDomainError(sender, "improper-addressing", "Cannot extract 'to' JID");
            return false;
        }

        // Find recipient session
        Optional<Session> recipient;
        if (toJid.contains("/")) {
            // Full JID - route to specific resource
            recipient = registry.getByFullJid(toJid);
        } else {
            // Bare JID - route to best available resource
            recipient = registry.getByContactId(toJid);
        }

        if (recipient.isEmpty()) {
            logger.fine("Local routing: recipient not online jid=" + toJid);
            return false; // Caller handles offline storage
        }

        boolean sent = recipient.get().writeXML(stanza);
        logger.fine("Local route: " + sender.getContactId()
                + " → " + toJid + " sent=" + sent);
        return sent;
    }

    /**
     * Placeholder for Server-to-Server federation routing.
     * When implemented: opens/reuses an S2S connection to the remote domain
     * and forwards the stanza.
     */
    private boolean routeToFederation(String domain, String stanza, Session sender) {
        logger.warning(S2S_NOT_IMPLEMENTED + " domain=" + domain);
        sendDomainError(sender, "remote-server-not-found",
                "Federation not yet supported");
        return false;
    }

    // =========================================================================
    // JID utilities
    // =========================================================================

    /**
     * Extracts the domain part from a full or bare JID.
     *
     * "alice@domain.com/mobile" → "domain.com"
     * "alice@domain.com"        → "domain.com"
     * "domain.com"              → "domain.com" (domain-only JID)
     */
    public static String extractDomain(String jid) {
        if (jid == null) return null;

        int atSign = jid.indexOf('@');
        int slash = jid.indexOf('/');

        if (atSign == -1) {
            // No @ sign - could be a domain-only JID
            return slash == -1 ? jid : jid.substring(0, slash);
        }

        return slash == -1
                ? jid.substring(atSign + 1)
                : jid.substring(atSign + 1, slash);
    }

    /**
     * Extracts the local part (username) from a JID.
     *
     * "alice@domain.com/mobile" → "alice"
     * "alice@domain.com"        → "alice"
     */
    public static String extractLocal(String jid) {
        if (jid == null) return null;
        int atSign = jid.indexOf('@');
        return atSign == -1 ? null : jid.substring(0, atSign);
    }

    /**
     * Extracts the bare JID (no resource) from a full JID.
     *
     * "alice@domain.com/mobile" → "alice@domain.com"
     */
    public static String toBareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    /**
     * Validates that a JID is structurally correct.
     *
     * Rules (simplified from RFC 7622):
     *  - Must have exactly one '@'
     *  - Local part must not be empty
     *  - Domain part must not be empty
     *  - Total length must not exceed 3071 bytes
     *  - Cannot contain control characters
     */
    public static boolean isValidJid(String jid) {
        if (jid == null || jid.isBlank()) return false;
        if (jid.length() > 3071) return false;

        int atSign = jid.indexOf('@');
        if (atSign <= 0) return false; // No @ or empty local part

        String local = jid.substring(0, atSign);
        String rest = jid.substring(atSign + 1);

        String domain;
        String resource = null;
        int slash = rest.indexOf('/');
        if (slash == -1) {
            domain = rest;
        } else {
            domain = rest.substring(0, slash);
            resource = rest.substring(slash + 1);
        }

        if (domain.isBlank()) return false;
        if (local.isBlank()) return false;
        if (resource != null && resource.isBlank()) return false;

        // No control characters
        for (char c : jid.toCharArray()) {
            if (Character.isISOControl(c)) return false;
        }

        return true;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Extracts the 'to' attribute value from a raw XML stanza string.
     * Simple string parsing - avoids re-parsing full XML for a single attribute.
     */
    private String extractToJid(String stanza) {
        // Look for: to='value' or to="value"
        int toIdx = stanza.indexOf("to='");
        if (toIdx != -1) {
            int start = toIdx + 4;
            int end = stanza.indexOf("'", start);
            if (end != -1) return stanza.substring(start, end);
        }

        toIdx = stanza.indexOf("to=\"");
        if (toIdx != -1) {
            int start = toIdx + 4;
            int end = stanza.indexOf("\"", start);
            if (end != -1) return stanza.substring(start, end);
        }

        return null;
    }

    /**
     * Sends an XMPP stream-level domain routing error back to the sender.
     */
    private void sendDomainError(Session sender, String condition, String text) {
        sender.writeXML(String.format(
                "<stream:error>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                        "<text xmlns='urn:ietf:params:xml:ns:xmpp-streams'>%s</text>" +
                        "</stream:error>",
                condition, escapeXml(text)
        ));
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}