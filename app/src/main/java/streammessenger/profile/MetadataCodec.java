package streammessenger.profile;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

final class MetadataCodec {
    private MetadataCodec() {}

    static String encode(Map<String, String> m) {
        if (m == null || m.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (var e : new TreeMap<>(m).entrySet()) {          // sorted → stable representation
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Map<String, String> decode(String s) {
        if (s == null || s.isEmpty()) return Map.of();
        Map<String, String> m = new HashMap<>();
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
