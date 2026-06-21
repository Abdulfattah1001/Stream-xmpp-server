package streammessenger.muc;

import java.util.*;

/** Bounded ring buffer of recent messages, replayed to new joiners. */
public final class RoomHistory {
    public static final class Entry {
        public final String fromNick;
        public final String body;   // plaintext, OR opaque encrypted XML
        public final boolean encrypted;
        Entry(String fromNick, String body, boolean encrypted) {
            this.fromNick = fromNick; this.body = body; this.encrypted = encrypted;
        }
    }
    private final int capacity;
    private final Deque<Entry> ring = new ArrayDeque<>();

    public RoomHistory(int capacity) { this.capacity = Math.max(0, capacity); }

    public synchronized void append(String fromNick, String body, boolean encrypted) {
        if (capacity == 0) return;
        if (ring.size() == capacity) ring.removeFirst();
        ring.addLast(new Entry(fromNick, body, encrypted));
    }
    public synchronized List<Entry> tail(int n) {
        int skip = Math.max(0, ring.size() - n);
        List<Entry> out = new ArrayList<>();
        int i = 0;
        for (Entry e : ring) if (i++ >= skip) out.add(e);
        return out;
    }
}