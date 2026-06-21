package streammessenger.muc.exceptions;

public class MucException extends RuntimeException {
    public enum Code {
        GROUP_NOT_FOUND("item-not-found"),
        NOT_MEMBER("forbidden"),
        NOT_AUTHORIZED("not-authorized"),
        ROOM_FULL("not-allowed"),
        ALREADY_MEMBER("conflict"),
        BANNED("forbidden"),
        INVITE_EXPIRED("not-acceptable"),
        INVITE_INVALID("bad-request"),
        MEMBERSHIP_APPROVAL_REQUIRED("registration-required"),
        ANNOUNCEMENT_MODE("forbidden"),
        VALIDATION_ERROR("bad-request"),
        INTERNAL("internal-server-error");

        public final String xmppCondition;
        Code(String xmppCondition) { this.xmppCondition = xmppCondition; }
    }

    private final Code code;

    public MucException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() { return code; }
}
