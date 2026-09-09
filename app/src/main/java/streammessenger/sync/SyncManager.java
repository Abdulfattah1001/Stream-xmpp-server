package streammessenger.sync;

import java.util.OptionalLong;
import java.util.Set;

import streammessenger.roster.RosterManager;
import streammessenger.session.Session;

public class SyncManager implements AutoCloseable{
    private final RosterManager rosterManager;

    public SyncManager(RosterManager rosterManager) {
        this.rosterManager = rosterManager;
    }

    public void onSessionOpen(Session session, OptionalLong cursor, String iqId) {
        Set<String> contacts = rosterManager.contactsOf(session.getUid());
    }

    @Override
    public void close() throws Exception {}
}
