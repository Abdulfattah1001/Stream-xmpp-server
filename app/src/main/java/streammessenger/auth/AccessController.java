package streammessenger.auth;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AccessController {

    private final Set<String> blockedIPs = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedRanges = ConcurrentHashMap.newKeySet(); // CIDR

    public boolean isAllowed(InetAddress address) {
        String ip = address.getHostAddress();

        if (blockedIPs.contains(ip)) return false;
        if (matchesAnyRange(ip)) return false;

        return true;
    }

    public void blockIP(String ip) { blockedIPs.add(ip); }

    public void blockRange(String cidr) { blockedRanges.add(cidr); }

    private boolean matchesAnyRange(String ip) {
        // CIDR matching logic
        return false;
    }
}