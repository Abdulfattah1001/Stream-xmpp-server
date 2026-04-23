package streammessenger.xep.sm;

/**
 * Represents a single outbound stanza that has been sent to the client
 * but not yet acknowledged via Stream Management (<a h='N'/>).
 *
 * If the client disconnects before acknowledging, these stanzas
 * are retransmitted on session resumption.
 *
 * seqNum: Server's outbound sequence number at the time of sending.
 *         Starts at 1 when SM is enabled, increments per stanza.
 *
 * xml:    The complete XML of the stanza, stored so it can be
 *         retransmitted verbatim.
 *
 * sentAt: Wall clock time when the stanza was sent.
 *         Can be used to expire old unacked stanzas if desired.
 */
public record UnackedStanza(
        long seqNum,
        String xml,
        long sentAt
) {
    /**
     * Convenience constructor that sets sentAt to now.
     */
    public UnackedStanza(long seqNum, String xml) {
        this(seqNum, xml, System.currentTimeMillis());
    }
}