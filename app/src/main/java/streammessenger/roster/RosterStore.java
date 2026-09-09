package streammessenger.roster;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Set;

public interface RosterStore {
    /** Contacts whose profile the user is entitled to see (subscription 'to' or 'both'). */
    Set<String> contactsOf(String userId) throws SQLException;
    /** Subset of candidates that are in the user's roster with a visible subscription. */
    Set<String> filterContacts(String userId, Collection<String> candidates) throws SQLException;
    /** Authorization: may viewer see owner's profile? */
    boolean canView(String viewerUserId, String ownerUserId) throws SQLException;
}
