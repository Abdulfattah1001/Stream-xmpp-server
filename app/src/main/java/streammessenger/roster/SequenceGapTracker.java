package streammessenger.roster;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tracks which change seqs above the low watermark (lw) have been dispatched. With a commit-ordered
 * sequencer the set is always empty after each batch. With a native sequence, a hole below the highest
 * seen seq is an in-flight (or rolled-back) transaction; we wait gapTimeout, then abandon it.
 * Invariant: every seq <= lw is dispatched or abandoned.
 */
final class SequenceGapTracker {
    private long lw;
    private final TreeSet<Long> done = new TreeSet<>();
    private final Map<Long, Long> gapFirstSeenNanos = new HashMap<>();
    private final long gapTimeoutNanos;
    private final int maxTracked;

    SequenceGapTracker(long initialLowWatermark, long gapTimeoutMs, int maxTracked) {
        this.lw = initialLowWatermark; this.gapTimeoutNanos = gapTimeoutMs * 1_000_000L; this.maxTracked = maxTracked;
    }

    synchronized long lowWatermark() { return lw; }
    synchronized boolean isDone(long seq) { return seq <= lw || done.contains(seq); }

    synchronized void markDone(long seq) {
        if (seq <= lw) return;
        done.add(seq);
        advance();
    }

    /** Record holes between lw and the highest seq seen in this poll. */
    synchronized void observeMax(long maxSeen, long nowNanos) {
        for (long s = lw + 1; s <= maxSeen; s++) {
            if (!done.contains(s)) gapFirstSeenNanos.putIfAbsent(s, nowNanos);
        }
        if (done.size() + gapFirstSeenNanos.size() > maxTracked) {   // memory bound: abandon the oldest holes
            expireOldest(done.size() + gapFirstSeenNanos.size() - maxTracked);
        }
    }

    synchronized void expire(long nowNanos) {
        Iterator<Map.Entry<Long, Long>> it = gapFirstSeenNanos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (nowNanos - e.getValue() >= gapTimeoutNanos) { done.add(e.getKey()); it.remove(); }
        }
        advance();
    }

    private void expireOldest(int n) {
        gapFirstSeenNanos.keySet().stream().sorted().limit(n).toList().forEach(s -> { done.add(s); gapFirstSeenNanos.remove(s); });
        advance();
    }

    private void advance() {
        while (!done.isEmpty() && done.first() == lw + 1) { done.pollFirst(); lw++; gapFirstSeenNanos.remove(lw); }
    }
}
