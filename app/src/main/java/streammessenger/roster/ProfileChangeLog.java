package streammessenger.roster;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ProfileChangeLog {
    /** Must be called inside the same transaction as the profile write. Returns the assigned seq. */
    long append(Connection c, String userId, long version, int fieldMask, Instant at) throws SQLException;
    /** Rows with seq > afterSeq, ascending, at most limit. Own connection, autocommit. */
    List<ProfileChange> readAfter(long afterSeq, int limit) throws SQLException;
    long maxSeq() throws SQLException;
    /** Oldest retained seq, or 0 if the log is empty. */
    long minSeq() throws SQLException;
    /** For each user in the collection, the latest version with seq > afterSeq. */
    List<ProfileVersion> latestSince(long afterSeq, Collection<String> userIds) throws SQLException;
    /** Deletes up to limit rows older than the instant. Returns rows deleted. */
    int prune(Instant olderThan, int limit) throws SQLException;
    /** Observability: persist this node's low watermark. */
    void recordNodeCursor(String nodeId, long seq) throws SQLException;
}
