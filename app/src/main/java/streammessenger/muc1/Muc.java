package streammessenger.muc;

/**
 * Centralised XEP-0045 namespace + element name constants.
 *
 * <p>Keeping these in one place avoids "magic string" drift between the
 * parser and the serializer — a classic source of interop bugs where the
 * writer emits {@code muc#user} but the reader checks {@code muc_user}.</p>
 */
public final class Muc {

    private Muc() {} // utility holder

    /** Sent by a client to *join* a room (presence to room/nick). */
    public static final String NS_MUC = "http://jabber.org/protocol/muc";

    /** Server -> client status/affiliation/role notifications. */
    public static final String NS_MUC_USER = "http://jabber.org/protocol/muc#user";

    /** Owner-only configuration & room creation. */
    public static final String NS_MUC_OWNER = "http://jabber.org/protocol/muc#owner";

    /** Admin operations: kick, ban, grant/revoke membership. */
    public static final String NS_MUC_ADMIN = "http://jabber.org/protocol/muc#admin";

    // --- Custom extension namespace for WhatsApp-style invite links ---
    // (Not part of XEP-0045; namespaced under our own domain to stay legal.)
    public static final String NS_MUC_INVITE_LINK = "urn:example:muc:invite-link:0";

    /** Common <x/> child element name. */
    public static final String EL_X = "x";
}