package streammessenger.muc1;
/* transport layer can render a spec-correct {@code <error/>} element.
 */
public final class MucException extends Exception {
    public final String condition; // e.g. "forbidden", "conflict"
    public final String type;      // "auth", "cancel", "modify", "wait"

    private MucException(String type, String condition, String msg) {
        super(msg);
        this.type = type;
        this.condition = condition;
    }
    public static MucException forbidden(String m)            { return new MucException("auth",   "forbidden", m); }
    public static MucException conflict(String m)             { return new MucException("cancel", "conflict", m); }
    public static MucException itemNotFound(String m)         { return new MucException("cancel", "item-not-found", m); }
    public static MucException registrationRequired(String m) { return new MucException("auth",   "registration-required", m); }
}