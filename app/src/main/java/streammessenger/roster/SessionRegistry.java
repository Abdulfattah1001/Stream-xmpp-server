package streammessenger.roster;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-local index. byUser: who is online here. interest: contact → local sessions that must be told
 * when that contact's profile changes (roster ∪ self). Sessions with rosters above the cap are kept
 * "unindexed" and matched by a batched DB roster filter in the worker instead (memory bound).
 * <p>
 * All mutations use ConcurrentHashMap.compute so an empty set is never removed while another thread
 * is adding to it (the classic computeIfAbsent/computeIfPresent orphan race).
 */
public final class SessionRegistry {
    private final ConcurrentHashMap<String, Set<ClientSession>> byUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<ClientSession>> interest = new ConcurrentHashMap<>();
    private final Set<ClientSession> unindexed = ConcurrentHashMap.newKeySet();
    private final int maxIndexedRoster;
    private final SyncMetrics metrics;

    public SessionRegistry(int maxIndexedRoster, SyncMetrics metrics) {
        this.maxIndexedRoster = maxIndexedRoster; this.metrics = metrics;
    }


    public void register(ClientSession s, Set<String> contacts) {

        byUser.compute(s.userId(), (k, set) -> { if (set == null) set = ConcurrentHashMap.newKeySet(); set.add(s); return set; });

        addInterest(s, s.userId());                     // own other devices must learn about own changes

        if (contacts.size() > maxIndexedRoster) {
            s.setUnindexed();
            unindexed.add(s);
        } else {
            Set<String> snapshot = Set.copyOf(contacts);
            s.setIndexed(snapshot);
            for (String c : snapshot) addInterest(s, c);
        }
        metrics.openSessions.incrementAndGet();
        metrics.sessionsRegistered.increment();
    }

    public void unregister(ClientSession s) {
        boolean removed = removeFrom(byUser, s.userId(), s);
        removeInterest(s, s.userId());
        for (String c : s.indexedContacts()) removeInterest(s, c);
        unindexed.remove(s);
        if (removed) metrics.openSessions.decrementAndGet();
    }

    /** Live view; safe to iterate concurrently (weakly consistent). */
    public Set<ClientSession> interestedIn(String contactId) {
        return interest.getOrDefault(contactId, Set.of());
    }
    public Set<ClientSession> sessionsOf(String userId) { return byUser.getOrDefault(userId, Set.of()); }
    public Collection<ClientSession> unindexedSessions() { return unindexed; }
    public boolean hasUnindexed() { return !unindexed.isEmpty(); }

    /** Roster service hooks. */
    public void onRosterAdded(String userId, String contactId) {
        for (ClientSession s : sessionsOf(userId)) if (s.isIndexed()) {
            // indexedContacts is an immutable snapshot used for cleanup; interest map is authoritative for dispatch.
            addInterest(s, contactId);
            Set<String> n = new HashSet<>(s.indexedContacts()); n.add(contactId); s.setIndexed(Set.copyOf(n));
        }
    }

    public void onRosterRemoved(String userId, String contactId) {
        for (ClientSession s : sessionsOf(userId)) if (s.isIndexed()) {
            removeInterest(s, contactId);
            Set<String> n = new HashSet<>(s.indexedContacts()); n.remove(contactId); s.setIndexed(Set.copyOf(n));
        }
    }

    private void addInterest(ClientSession s, String contactId) {
        interest.compute(contactId, (k, set) -> {
            if (set == null) { set = ConcurrentHashMap.newKeySet(); metrics.interestKeys.incrementAndGet(); }
            if (set.add(s)) metrics.interestEntries.incrementAndGet();
            return set;
        });
    }
    private void removeInterest(ClientSession s, String contactId) {
        if (removeFrom(interest, contactId, s)) metrics.interestEntries.decrementAndGet();
    }
    private boolean removeFrom(ConcurrentHashMap<String, Set<ClientSession>> map, String key, ClientSession s) {
        boolean[] removed = {false};
        map.computeIfPresent(key, (k, set) -> {
            removed[0] = set.remove(s);
            if (set.isEmpty()) { if (map == interest) metrics.interestKeys.decrementAndGet(); return null; }
            return set;
        });
        return removed[0];
    }
}