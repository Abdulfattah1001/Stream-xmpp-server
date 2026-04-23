package streammessenger.session;


import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry for all active sessions.
 *
 * Index structure:
 *
 *   PRIMARY:   uid → Session
 *              One entry per TCP connection, from accept() to socket close.
 *
 *   SECONDARY: contactId → Set<uid>
 *              One contactId can map to MULTIPLE uids (multiple resources).
 *              e.g. alice@domain logged in on phone AND laptop simultaneously.
 *
 * This fixes the original code's broken single-uid-per-contact model.
 */
public final class SessionRegistry {

    private static final Logger logger = Logger.getLogger(SessionRegistry.class.getName());

    // uid → Session (primary index)
    private final ConcurrentHashMap<String, Session> byUid =
            new ConcurrentHashMap<>();

    // contactId → Set of uids (secondary index, supports multiple resources)
    private final ConcurrentHashMap<String, Set<String>> contactToUids =
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
     * At this point the session has no contactId - just a uid.
     */
    public void register(Session session) {
        byUid.put(session.getUid(), session);
        logger.fine("Registered session uid=" + session.getUid());
    }

    /**
     * Binds a contactId to a session after successful authentication.
     *
     * Supports multiple resources: Alice can log in from phone AND laptop.
     * Both sessions get added to the Set for alice@domain.
     *
     * If the same uid is already bound (re-auth on same connection),
     * this is idempotent.
     */
    public void bindAuthenticatedSession(String contactId, Session session) {
        contactToUids.compute(contactId, (k, existingUids) -> {
            Set<String> uids = existingUids != null
                    ? existingUids
                    : ConcurrentHashMap.newKeySet();
            uids.add(session.getUid());
            return uids;
        });
        logger.info("Session bound: contactId=" + contactId
                + " uid=" + session.getUid()
                + " totalResourcesForContact=" + contactToUids.get(contactId).size());
    }

    // =========================================================================
    // Removal
    // =========================================================================

    /**
     * Removes a session from both indexes.
     * Safe to call multiple times.
     */
    public void remove(Session session) {
        removeByUid(session.getUid(), session.getContactId());
    }

    public void removeByUid(String uid, String contactId) {
        byUid.remove(uid);

        if (contactId != null) {
            contactToUids.computeIfPresent(contactId, (k, uids) -> {
                uids.remove(uid);
                // If no more resources for this contact, remove the entry entirely
                return uids.isEmpty() ? null : uids;
            });
        }

        logger.fine("Removed session uid=" + uid);
    }

    // =========================================================================
    // Lookup
    // =========================================================================

    public Optional<Session> getByUid(String uid) {
        return Optional.ofNullable(byUid.get(uid));
    }

    /**
     * Returns ONE session for a contactId.
     *
     * When a user has multiple resources (phone + laptop), this returns
     * the one with the highest priority, or the most recently active one.
     *
     * Used by MessageHandler when routing to a bare JID (no resource specified).
     */
    public Optional<Session> getByContactId(String contactId) {
        Set<String> uids = contactToUids.get(contactId);
        if (uids == null || uids.isEmpty()) return Optional.empty();

        // Pick the most recently active session among all resources
        return uids.stream()
                .map(byUid::get)
                .filter(Objects::nonNull)
                .filter(Session::isAuthenticated)
                .max(Comparator.comparingLong(Session::getLastActivity));
    }

    /**
     * Returns ALL sessions for a contactId (all resources/devices).
     *
     * Used by:
     *  - RosterManager: push roster updates to all of user's devices
     *  - PresenceHandler: send presence to all resources
     *  - StreamManagement: find the right session to resume
     *
     * This is the method that was MISSING from the original code
     * and caused issues in RosterManager and SubscriptionHandler.
     */
    public List<Session> getSessionsByContactId(String contactId) {
        Set<String> uids = contactToUids.get(contactId);
        if (uids == null || uids.isEmpty()) return Collections.emptyList();

        return uids.stream()
                .map(byUid::get)
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
        return Collections.unmodifiableCollection(byUid.values());
    }

    /**
     * Returns true if a contactId has at least one authenticated session.
     */
    public boolean isOnline(String contactId) {
        return getByContactId(contactId).isPresent();
    }

    public int size() {
        return byUid.size();
    }

    /**
     * Backward-compatible raw map access.
     * Prefer typed methods above for new code.
     */
    public ConcurrentHashMap<String, Session> getRawMap() {
        return byUid;
    }
}