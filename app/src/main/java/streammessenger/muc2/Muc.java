package streammessenger.muc;

/** Central home for all XEP-0045 namespace strings, so reader & writer agree. */
public final class Muc {
    private Muc() {}

    public static final String NS_MUC        = "http://jabber.org/protocol/muc";
    public static final String NS_MUC_USER   = "http://jabber.org/protocol/muc#user";
    public static final String NS_MUC_ADMIN  = "http://jabber.org/protocol/muc#admin";

    /** Our own (non-standard) namespaces for WhatsApp-style extras. */
    public static final String NS_CREATE       = "urn:example:muc";
    public static final String NS_INVITE_LINK  = "urn:example:muc:invite-link:0";

    /** Standard stanza-error namespace. */
    public static final String NS_STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas";
}