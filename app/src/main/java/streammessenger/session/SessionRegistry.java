package streammessenger.session;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry for all active sessions.
 * <p>
 * Index structure:
 * <p>
 *   PRIMARY:   uid → Session
 *              One entry per TCP connection, from accept() to socket close.
 * <p>
 *   SECONDARY: contactId → Set<uid>
 *              One contactId can map to MULTIPLE uids (multiple resources).
 *              e.g. alice@domain logged in on phone AND laptop simultaneously.
 * <p>
 * This fixes the original code's broken single-uid-per-contact model.
 */
public final class SessionRegistry {

    /**
     * For the context, contactId -> u_1kufh2; uid -> 32bits number e.t.c
     */

    private static final Logger logger = Logger.getLogger(SessionRegistry.class.getName());

    // uid → Session (primary index)
    // TODO: To be renamed to bySessionId
    private final ConcurrentHashMap<String, Session> bySessionId =
            new ConcurrentHashMap<>();

    // userId → Set of sessionIds (secondary index, supports multiple resources)
    private final ConcurrentHashMap<String, Set<String>> contactIdsToSessionIds =
            new ConcurrentHashMap<>();

    private static final SessionRegistry INSTANCE = new SessionRegistry();

    private SessionRegistry() {}

    public static SessionRegistry getInstance() {
        return INSTANCE;
    }

    // =========================================================================
    // Registration
    // =========================================================================

    /**
     * Registers a new session immediately when a TCP connection is accepted.
     * At this point the session has no contactId - just a sessionId.
     */
    public void register(Session session) {
        bySessionId.put(session.getSessionId(), session);
    }

    /**
     * Binds a userId to a session after successful authentication.
     * <p>
     * Supports multiple resources: Alice can log in from phone AND laptop.
     * Both sessions get added to the Set for alice@domain.
     * <p>
     * If the same sessionId is already bound (re-auth on same connection),
     * this is idempotent.
     */
    public void bindAuthenticatedSession(String contactId, Session session) {
        contactIdsToSessionIds.compute(contactId, (k, existingUids) -> {
            Set<String> sessionIds = existingUids != null
                    ? existingUids
                    : ConcurrentHashMap.newKeySet();
            sessionIds.add(session.getSessionId());
            return sessionIds;
        });
        logger.info("Session bound: contactId=" + contactId
                + " sessionId=" + session.getSessionId()
                + " totalResourcesForContact=" + contactIdsToSessionIds.get(contactId).size());
    }

    // =========================================================================
    // Removal
    // =========================================================================

    /**
     * Removes a session from both indexes.
     * Safe to call multiple times.
     */
    public void remove(Session session) {
        removeBySessionId(session.getSessionId(), session.getContactId());
    }

    public void removeBySessionId(String sessionId, String contactId) {
        bySessionId.remove(sessionId);

        if (contactId != null) {
            contactIdsToSessionIds.computeIfPresent(contactId, (k, uids) -> {
                uids.remove(sessionId);
                // If no more resources for this contact, remove the entry entirely
                return uids.isEmpty() ? null : uids;
            });
        }

        logger.fine("Removed session sessionId=" + sessionId);
    }

    // =========================================================================
    // Lookup
    // =========================================================================

    public Optional<Session> getBySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    /**
     * Returns ONE session for a contactId.
     * <p>
     * When a user has multiple resources (phone + laptop), this returns
     * the one with the highest priority, or the most recently active one.
     * <p>
     * Used by MessageHandler when routing to a bare JID (no resource specified).
     */
    public Optional<Session> getByContactId(String contactId) {
        Set<String> uids = contactIdsToSessionIds.get(contactId);
        if (uids == null || uids.isEmpty()) return Optional.empty();

        // Pick the most recently active session among all resources
        return uids.stream()
                .map(bySessionId::get)
                .filter(Objects::nonNull)
                .filter(Session::isAuthenticated)
                .max(Comparator.comparingLong(Session::getLastActivity));
    }

    /**
     * Returns ALL sessions for a contactId (all resources/devices).
     * <p>
     * Used by:
     *  - RosterManager: push roster updates to all of user's devices
     *  - PresenceHandler: send presence to all resources
     *  - StreamManagement: find the right session to resume
     * <p>
     * This is the method that was MISSING from the original code
     * and caused issues in RosterManager and SubscriptionHandler.
     */
    public List<Session> getSessionsByContactId(String contactId) {
        Set<String> uids = contactIdsToSessionIds.get(contactId);
        if (uids == null || uids.isEmpty()) return Collections.emptyList();

        return uids.stream()
                .map(bySessionId::get)
                .filter(Objects::nonNull)
                .filter(Session::isAuthenticated)
                .collect(Collectors.toList());
    }

    /**
     * Returns the specific session for a full JID (user@domain/resource).
     * Used when routing to a specific resource.
     */
    public Optional<Session> getByFullJid(String fullJid) {
        // fullJid = "alice@domain.com/mobile"
        if (!fullJid.contains("/")) {
            return getByContactId(fullJid); // bare JID fallback
        }

        String contactId = fullJid.substring(0, fullJid.indexOf('/'));
        String resource = fullJid.substring(fullJid.indexOf('/') + 1);

        return getSessionsByContactId(contactId).stream()
                .filter(s -> resource.equals(s.getResource()))
                .findFirst();
    }

    /**
     * Returns all sessions (all users, all resources).
     * Used by: SessionReaper, PresenceHandler broadcast, shutdown.
     */
    public Collection<Session> getAllSessions() {
        return Collections.unmodifiableCollection(bySessionId.values());
    }

    /**
     * Returns true if a contactId has at least one authenticated session.
     */
    public boolean isOnline(String contactId) {
        return getByContactId(contactId).isPresent();
    }

    public int size() {
        return bySessionId.size();
    }

    /**
     * Backward-compatible raw map access.
     * Prefer typed methods above for new code.
     */
    public ConcurrentHashMap<String, Session> getRawMap() {
        return bySessionId;
    }
}