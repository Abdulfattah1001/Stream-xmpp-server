package streammessenger.util;


import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

// A client can send a 1GB stanza and crash your server
public final class StanzaSizeLimitingInputStream extends FilterInputStream {

    private static final int MAX_STANZA_SIZE = 65_536; // 64KB per RFC 6120 recommendation
    private long bytesRead = 0;

    public StanzaSizeLimitingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        if (bytesRead >= MAX_STANZA_SIZE) {
            throw new IOException("Stanza size limit exceeded: " + MAX_STANZA_SIZE + " bytes");
        }
        int result = super.read();
        if (result != -1) bytesRead++;
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (bytesRead >= MAX_STANZA_SIZE) {
            throw new IOException("Stanza size limit exceeded");
        }
        int result = super.read(b, off, Math.min(len, (int)(MAX_STANZA_SIZE - bytesRead)));
        if (result != -1) bytesRead += result;
        return result;
    }

    public void resetCounter() {
        bytesRead = 0; // Reset between stanzas
    }
}