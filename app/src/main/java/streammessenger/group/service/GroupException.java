package streammessenger.group.service;

public class GroupException extends RuntimeException {

    public enum Code {
        NOT_FOUND("item-not-found"),
        NOT_MEMBER("forbidden"),
        NOT_AUTHORIZED("not-authorized"),
        ALREADY_MEMBER("conflict"),
        GROUP_FULL("not-allowed"),
        VALIDATION("bad-request"),
        INTERNAL("internal-server-error");

        public final String xmppCondition;
        Code(String c) { this.xmppCondition = c; }
    }

    private final Code code;

    public GroupException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() { return code; }
}