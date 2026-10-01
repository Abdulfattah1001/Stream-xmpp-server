package streammessenger.roster;


import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

public final class ProfileWire {
    public static final String NS = "urn:xmpp:profile-sync:1";
    private ProfileWire() {}

    public record Invalidation(String userId, long version, long seq) {}
    public record GetItem(String userId, long have) {}                 // have = -1 when the client has nothing
    public record ProfileResult(String userId, Profile profile, boolean unchanged, String error) {
        static ProfileResult full(Profile p) { return new ProfileResult(p.userId(), p, false, null); }
        static ProfileResult unchanged(String u, long v) { return new ProfileResult(u, null, true, null); }
        static ProfileResult error(String u, String cond) { return new ProfileResult(u, null, false, cond); }
    }

    public sealed interface Request permits GetRequest, AckRequest, SyncRequest, FullSyncRequest {}
    public record GetRequest(String id, List<GetItem> items) implements Request {}
    public record AckRequest(String id, long seq) implements Request {}
    public record SyncRequest(String id, OptionalLong since) implements Request {}
    public record FullSyncRequest(String id, Map<String, Long> have) implements Request {}

    public static final class ProtocolException extends Exception {
        public ProtocolException(String m, Throwable c) { super(m, c); }
        public ProtocolException(String m) { super(m); }
    }

    // ---------------------------------------------------------------- encode

    public static String invalidate(List<Invalidation> items) {
        StringBuilder sb = new StringBuilder(64 + items.size() * 96);
        sb.append("<message><profile-invalidate xmlns=\"").append(NS).append("\">");
        for (Invalidation i : items) {
            sb.append("<item user=\"").append(esc(i.userId())).append("\" version=\"").append(i.version())
              .append("\" seq=\"").append(i.seq()).append("\"/>");
        }
        return sb.append("</profile-invalidate></message>").toString();
    }

    public static String syncRequired() {
        return "<message><profile-sync-required xmlns=\"" + NS + "\"/></message>";
    }

    public static String profiles(String id, List<ProfileResult> results, Map<String, Long> versionsForUnchanged) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<iq type=\"result\" id=\"").append(esc(id)).append("\"><profiles xmlns=\"").append(NS).append("\">");
        for (ProfileResult r : results) {
            sb.append("<profile user=\"").append(esc(r.userId())).append('"');
            if (r.error() != null) { sb.append(" error=\"").append(esc(r.error())).append("\"/>"); continue; }
            if (r.unchanged()) { sb.append(" version=\"").append(versionsForUnchanged.get(r.userId())).append("\" unchanged=\"true\"/>"); continue; }
            Profile p = r.profile();
            sb.append(" version=\"").append(p.version()).append("\" updated=\"").append(p.updatedAt()).append("\">");
            if (p.displayName() != null) sb.append("<display-name>").append(esc(p.displayName())).append("</display-name>");
            if (p.avatarHash() != null) sb.append("<avatar hash=\"").append(esc(p.avatarHash())).append("\"/>");
            if (p.statusText() != null) sb.append("<status>").append(esc(p.statusText())).append("</status>");
            for (var e : new TreeMap<>(p.metadata()).entrySet())
                sb.append("<meta key=\"").append(esc(e.getKey())).append("\">").append(esc(e.getValue())).append("</meta>");
            sb.append("</profile>");
        }
        return sb.append("</profiles></iq>").toString();
    }

    public static String syncResult(String id, long since, long upto, List<ProfileVersion> changed) {
        StringBuilder sb = new StringBuilder(128 + changed.size() * 80);
        sb.append("<iq type=\"result\" id=\"").append(esc(id)).append("\"><profile-sync xmlns=\"").append(NS)
          .append("\" since=\"").append(since).append("\" upto=\"").append(upto).append("\">");
        for (ProfileVersion v : changed)
            sb.append("<changed user=\"").append(esc(v.userId())).append("\" version=\"").append(v.version()).append("\"/>");
        return sb.append("</profile-sync></iq>").toString();
    }

    public static String syncFullRequired(String id, long upto) {
        return "<iq type=\"result\" id=\"" + esc(id) + "\"><profile-sync xmlns=\"" + NS + "\" full=\"true\" upto=\"" + upto + "\"/></iq>";
    }

    public static String error(String id, String condition, String text) {
        return "<iq type=\"error\" id=\"" + esc(id) + "\"><error type=\"cancel\"><" + condition
             + " xmlns=\"urn:ietf:params:xml:ns:xmpp-stanzas\"/><text>" + esc(text) + "</text></error></iq>";
    }

    public static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;"); case '>' -> sb.append("&gt;"); case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;"); case '\'' -> sb.append("&apos;");
                default -> { if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c); }   // strip illegal control chars
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- decode

    public static Request parse(String xml) throws ProtocolException {
        Element root, payload;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document d = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            root = d.getDocumentElement();
            payload = NS.equals(root.getNamespaceURI()) ? root : firstChildInNs(root);
        } catch (Exception e) { throw new ProtocolException("malformed stanza", e); }
        if (payload == null) throw new ProtocolException("no profile-sync payload");
        String id = root.getAttribute("id");
        switch (payload.getLocalName()) {
            case "profile-get" -> {
                List<GetItem> items = new ArrayList<>();
                for (Element it : children(payload, "item")) items.add(new GetItem(req(it, "user"), optLong(it, "have", -1)));
                if (items.isEmpty() || items.size() > 200) throw new ProtocolException("profile-get needs 1..200 items");
                return new GetRequest(id, items);
            }
            case "profile-ack" -> { return new AckRequest(id, optLong(payload, "seq", -1)); }
            case "profile-sync" -> {
                if ("true".equals(payload.getAttribute("full"))) {
                    Map<String, Long> have = new HashMap<>();
                    for (Element h : children(payload, "have")) have.put(req(h, "user"), optLong(h, "version", -1));
                    return new FullSyncRequest(id, have);
                }
                String since = payload.getAttribute("since");
                return new SyncRequest(id, since.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(since)));
            }
            default -> throw new ProtocolException("unknown element " + payload.getLocalName());
        }
    }

    private static Element firstChildInNs(Element root) {
        NodeList nl = root.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++)
            if (nl.item(i) instanceof Element e && NS.equals(e.getNamespaceURI())) return e;
        return null;
    }
    private static List<Element> children(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++)
            if (nl.item(i) instanceof Element e && local.equals(e.getLocalName())) out.add(e);
        return out;
    }
    private static String req(Element e, String attr) throws ProtocolException {
        String v = e.getAttribute(attr);
        if (v.isEmpty() || v.length() > 255) throw new ProtocolException("bad attribute " + attr);
        return v;
    }
    private static long optLong(Element e, String attr, long dflt) throws ProtocolException {
        String v = e.getAttribute(attr);
        if (v.isEmpty()) return dflt;
        try { return Long.parseLong(v); } catch (NumberFormatException ex) { throw new ProtocolException("bad number " + attr); }
    }
}
