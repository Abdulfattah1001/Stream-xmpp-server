package streammessenger.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import streammessenger.profile.Profile;
import streammessenger.roster.RosterManager;
import streammessenger.session.Session;

public class SyncManager implements AutoCloseable {
    private final static String PROFILE_NS = "urn:xmpp:profile-sync:1";
    private final RosterManager rosterManager;
    private final LinkedHashMap<String, Change> changeCache;

    public SyncManager(RosterManager rosterManager) {
        this.rosterManager = rosterManager;
        this.changeCache = new LinkedHashMap<>(1024, 0.75f, true){
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Change> e) {
                return size() > 1000;
            }
        };

    }

    public String handle(Session session) { return null; }

    public void onSessionOpen(Session session, OptionalLong cursor, String iqId) {
        Set<String> contacts = rosterManager.contactsOf(session.getUid());
    }

    void onChangeObserver(Change change) {
        synchronized (changeCache) {
            Change cur = changeCache.get(change.userId());
            if(cur != null && cur.version() < change.version()) changeCache.remove(change.userId());
        }
    }

    @Override
    public void close() throws Exception {}
}
