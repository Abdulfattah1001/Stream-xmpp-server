package streammessenger.connection;



import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import streammessenger.config.ServerConfig;

/**
 * Upgrades a plain TCP socket to a TLS socket.
 * <p>
 * SSLContext is injected from ServerConfig - it is created ONCE at startup
 * and reused here. This avoids the KeyStore/certificate parsing overhead
 * that occurred per-connection in the original implementation.
 */
public final class TLSUpgrader {

    private static final Logger logger = Logger.getLogger(TLSUpgrader.class.getName());

    // Allowed protocols - explicitly forbid SSLv3, TLSv1.0, TLSv1.1
    private static final String[] ALLOWED_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

    // Explicitly excluded cipher patterns
    private static final String[] EXCLUDED_CIPHER_PATTERNS = {
        "NULL", "anon", "EXPORT", "DES", "RC4", "MD5", "3DES", "IDEA"
    };

    private final ServerConfig config;

    public TLSUpgrader(ServerConfig config) {
        this.config = config;
    }

    /**
     * Wraps the given plain socket in an SSLSocket and completes the TLS handshake.
     *
     * @param plainSocket The existing TCP socket
     * @return A fully negotiated SSLSocket ready for encrypted I/O
     * @throws IOException If the upgrade or handshake fails
     */
    public SSLSocket upgrade(Socket plainSocket) throws IOException {
        SSLSocket sslSocket = (SSLSocket) config.getSslContext()
                .getSocketFactory()
                .createSocket(
                        plainSocket,
                        plainSocket.getInetAddress().getHostAddress(),
                        plainSocket.getPort(),
                        true // autoClose: close plain socket when SSL socket is closed
                );

        sslSocket.setUseClientMode(false);
        sslSocket.setNeedClientAuth(false);
        sslSocket.setEnabledProtocols(filterProtocols(sslSocket));
        sslSocket.setEnabledCipherSuites(filterCiphers(sslSocket));

        // startHandshake() is explicit here so we can catch and log handshake failures
        // distinctly from I/O failures that occur later
        sslSocket.startHandshake();

        logger.info(String.format(
            "TLS handshake complete [protocol=%s, cipher=%s]",
            sslSocket.getSession().getProtocol(),
            sslSocket.getSession().getCipherSuite()
        ));

        return sslSocket;
    }

    /**
     * Filters the socket's supported protocols down to only the allowed ones.
     */
    private String[] filterProtocols(SSLSocket socket) {
        List<String> enabled = new ArrayList<>();
        for (String supported : socket.getSupportedProtocols()) {
            for (String allowed : ALLOWED_PROTOCOLS) {
                if (supported.equals(allowed)) {
                    enabled.add(supported);
                    break;
                }
            }
        }

        if (enabled.isEmpty()) {
            throw new IllegalStateException(
                "No acceptable TLS protocols available. " +
                "Server requires TLSv1.2 or TLSv1.3."
            );
        }

        return enabled.toArray(new String[0]);
    }

    /**
     * Filters out cipher suites known to be weak or broken.
     */
    private String[] filterCiphers(SSLSocket socket) {
        List<String> strong = new ArrayList<>();

        outer:
        for (String cipher : socket.getSupportedCipherSuites()) {
            for (String excluded : EXCLUDED_CIPHER_PATTERNS) {
                if (cipher.contains(excluded)) continue outer;
            }
            strong.add(cipher);
        }

        if (strong.isEmpty()) {
            throw new IllegalStateException("No strong cipher suites available.");
        }

        return strong.toArray(new String[0]);
    }
}