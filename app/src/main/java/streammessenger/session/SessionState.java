package streammessenger.session;

/**
 * Represents the lifecycle state of an XMPP session.
 *
 * State transitions:
 * INITIAL -> STREAM_OPENED -> STARTTLS_NEGOTIATED -> AUTHENTICATING -> AUTHENTICATED -> CLOSED
 */
public enum SessionState {
    /**
     * TCP connection accepted, nothing received yet
     */
    INITIAL,

    /**
     * Client sent opening <stream:stream> header
     */
    STREAM_OPENED,

    /**
     * TLS handshake completed successfully
     */
    STARTTLS_NEGOTIATED,

    /**
     * SASL authentication is in progress
     */
    AUTHENTICATING,

    /**
     * Client is fully authenticated and ready for stanza exchange
     */
    AUTHENTICATED,

    /**
     * Session is being terminated
     */
    CLOSING,

    /**
     * Session is fully terminated
     */
    CLOSED
}