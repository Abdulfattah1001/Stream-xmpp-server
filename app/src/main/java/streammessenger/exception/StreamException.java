package streammessenger.exception;

/**
 * Represents an XMPP stream-level error (RFC 6120 §4.9).
 * When thrown, the server should send a stream:error stanza 
 * and close the stream.
 */
public class StreamException extends Exception {

    /**
     * XMPP stream error conditions per RFC 6120 §4.9.3
     */
    public enum Condition {
        BAD_FORMAT("bad-format"),
        BAD_NAMESPACE_PREFIX("bad-namespace-prefix"),
        CONFLICT("conflict"),
        CONNECTION_TIMEOUT("connection-timeout"),
        HOST_GONE("host-gone"),
        HOST_UNKNOWN("host-unknown"),
        IMPROPER_ADDRESSING("improper-addressing"),
        INTERNAL_SERVER_ERROR("internal-server-error"),
        INVALID_FROM("invalid-from"),
        INVALID_XML("invalid-xml"),
        NOT_AUTHORIZED("not-authorized"),
        NOT_WELL_FORMED("not-well-formed"),
        POLICY_VIOLATION("policy-violation"),
        REMOTE_CONNECTION_FAILED("remote-connection-failed"),
        RESET("reset"),
        RESOURCE_CONSTRAINT("resource-constraint"),
        RESTRICTED_XML("restricted-xml"),
        SYSTEM_SHUTDOWN("system-shutdown"),
        UNDEFINED_CONDITION("undefined-condition"),
        UNSUPPORTED_ENCODING("unsupported-encoding"),
        UNSUPPORTED_FEATURE("unsupported-feature"),
        UNSUPPORTED_STANZA_TYPE("unsupported-stanza-type"),
        UNSUPPORTED_VERSION("unsupported-version");

        private final String xmlValue;

        Condition(String xmlValue) {
            this.xmlValue = xmlValue;
        }

        public String getXmlValue() {
            return xmlValue;
        }
    }

    private final Condition condition;

    public StreamException(String message, Condition condition) {
        super(message);
        this.condition = condition;
    }

    public Condition getCondition() {
        return condition;
    }
}