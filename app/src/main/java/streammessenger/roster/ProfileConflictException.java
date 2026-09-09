package streammessenger.roster;

/** Optimistic-concurrency failure: caller supplied an expectedVersion that no longer matches. */
public final class ProfileConflictException extends RuntimeException {
    private final long expected, actual;
    public ProfileConflictException(String userId, long expected, long actual) {
        super("profile " + userId + " is at version " + actual + ", expected " + expected);
        this.expected = expected; this.actual = actual;
    }
    public long expected() { return expected; }
    public long actual() { return actual; }
}
