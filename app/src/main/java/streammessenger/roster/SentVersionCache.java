package streammessenger.roster;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded LRU of the highest version already sent to a session per contact (dedup/out-of-order guard). */
final class SentVersionCache {
    private final LinkedHashMap<String, Long> map;

    SentVersionCache(int capacity) {
        this.map = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { return size() > capacity; }
        };
    }
    synchronized boolean covers(String userId, long version) {
        Long v = map.get(userId); return v != null && v >= version;
    }
    synchronized void record(String userId, long version) { map.merge(userId, version, Math::max); }
}
