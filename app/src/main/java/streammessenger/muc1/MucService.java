package streammessenger.muc1;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MUC <i>service</i> domain (e.g. {@code conference.example.com}).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Own the room registry (create-on-first-join semantics for new rooms).</li>
 *   <li>Route inbound stanzas addressed to {@code room@service[/nick]}.</li>
 *   <li>Dispatch the {@link Room.Outbound} packets the room produces onto the
 *       real network via the injected {@link StanzaRouter}.</li>
 * </ul>
 *
 * <p>I/O is injected (dependency inversion) so the service is testable and so
 * it doesn't care whether you're on TCP, WebSocket, or an in-VM bus.</p>
 */
public final class MucService {

    /** Hook into your existing infrastructure's outbound path. */
    public interface StanzaRouter {
        /** Deliver a fully-rendered stanza to a JID. */
        void route(String toJid, String stanzaXml);
    }

    private final String serviceDomain;
    private final StanzaRouter router;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public MucService(String serviceDomain, StanzaRouter router) {
        this.serviceDomain = serviceDomain;
        this.router = router;
    }

    /**
     * Create a persistent room (WhatsApp "create group").
     *
     * @return the created room
     * @throws MucException with {@code conflict} if the room already exists
     */
    public Room createRoom(String localPart, String ownerJid, RoomConfig config)
            throws MucException {
        String roomJid = localPart + "@" + serviceDomain;
        Room created = new Room(roomJid, ownerJid, config);
        if (rooms.putIfAbsent(roomJid, created) != null)
            throw MucException.conflict("Room already exists: " + roomJid);
        return created;
    }

    public Room room(String roomJid) { return rooms.get(roomJid); }

    /**
     * Convenience entry point used by command handlers: run a room command and
     * flush its outbound packets onto the wire.
     */
    public void dispatch(Iterable<Room.Outbound> outbound) {
        for (Room.Outbound o : outbound) {
            // We inject the 'to' attribute at dispatch time, since the same
            // logical presence is addressed to many recipients.
            String xml = injectTo(o.stanza.render(), o.toJid);
            router.route(o.toJid, xml);
        }
    }

    /**
     * Cheap 'to' injection. In your real serializer you'd set the attribute on
     * the stanza object before writing rather than string-splicing — this is a
     * stand-in to keep the example self-contained.
     */
    private String injectTo(String xml, String to) {
        int gt = xml.indexOf('>');
        return xml.substring(0, gt) + " to=\"" + to + "\"" + xml.substring(gt);
    }
}