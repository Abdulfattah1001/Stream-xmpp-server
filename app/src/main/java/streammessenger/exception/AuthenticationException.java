package streammessenger.exception;

public class AuthenticationException extends Exception {
    
    public enum Reason {
        INVALID_CREDENTIALS,
        ACCOUNT_DISABLED,
        MECHANISM_NOT_SUPPORTED,
        MALFORMED_REQUEST
    }

    private final Reason reason;

    public AuthenticationException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}