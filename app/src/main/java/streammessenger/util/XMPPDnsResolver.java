package streammessenger.util;

import java.util.List;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;

// Clients and servers find us via DNS SRV records
// _xmpp-client._tcp.omnyrex.com -> your server IP
// Without this, clients can't auto-discover your server
public final class XMPPDnsResolver {

    public List<ServerRecord> resolveClient(String domain) throws NamingException {
        return resolveSrv("_xmpp-client._tcp." + domain);
    }

    public List<ServerRecord> resolveServer(String domain) throws NamingException {
        return resolveSrv("_xmpp-server._tcp." + domain);
    }

    private List<ServerRecord> resolveSrv(String query) throws NamingException {
        javax.naming.directory.InitialDirContext ctx =
                new javax.naming.directory.InitialDirContext();

        javax.naming.directory.Attributes attrs =
                ctx.getAttributes(query, new String[]{"SRV"});

        // Parse priority, weight, port, target
        // Sort by priority, then weight
        return parseSrvRecords(attrs.get("SRV"));
    }

    public record ServerRecord(int priority, int weight, int port, String host) {}

    private List<ServerRecord> parseSrvRecords(Attribute attribute){return null;}
}