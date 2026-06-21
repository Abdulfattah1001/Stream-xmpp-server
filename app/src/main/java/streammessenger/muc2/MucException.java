package streammessenger.muc;

/** A domain failure that knows its XMPP error condition, for clean <error/> replies. */
public final class MucException extends Exception {
    public final String type;       // "auth", "cancel", "modify", "wait"
    public final String condition;  // "forbidden", "conflict", ...

    private MucException(String type, String condition, String msg) {
        super(msg); this.type = type; this.condition = condition;
    }
    public static MucException forbidden(String m)            { return new MucException("auth",   "forbidden", m); }
    public static MucException conflict(String m)             { return new MucException("cancel", "conflict", m); }
    public static MucException itemNotFound(String m)         { return new MucException("cancel", "item-not-found", m); }
    public static MucException registrationRequired(String m) { return new MucException("auth",   "registration-required", m); }
}