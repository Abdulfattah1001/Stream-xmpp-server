package streammessenger.muc1;

/**
 * MUC status codes (XEP-0045 §17.3). We model only the ones we emit/consume.
 *
 * <p>Using an enum instead of raw ints makes server logic self-documenting and
 * makes the client's reaction switch exhaustive.</p>
 */
public enum MucStatus {
    /** "This presence refers to YOU." Always present in a self-presence. */
    SELF_PRESENCE(110),
    /** Room created and awaiting configuration (sent to the creator). */
    ROOM_CREATED(201),
    /** You were kicked. */
    KICKED(307),
    /** You were banned (affiliation -> outcast). */
    BANNED(301),
    /** Removed because you lost membership in a members-only room. */
    REMOVED_AFFILIATION_LOSS(321),
    /** Non-anonymous room: real JIDs are visible. */
    NON_ANONYMOUS(100);

    private final int code;
    MucStatus(int code) { this.code = code; }
    public int code() { return code; }
}
