package streammessenger.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Parses SASL PLAIN authentication (RFC 4616).
 * <p>
 * PLAIN format (Base64 encoded): [authzid]\0authcid\0passwd [JsonWebToken]
 * <p>
 * Note: PLAIN must only be offered after TLS is established.
 * Never offer PLAIN over unencrypted connections.
 */
public final class SASLMechanism {
    private SASLMechanism() {}
    public record Credentials(String username, String password) {}

    /**
     * Decodes a SASL PLAIN base64 payload into credentials.
     *
     * @param base64Payload The raw content of the XMPP <auth> element
     * @return Decoded credentials
     * @throws IllegalArgumentException if the payload is malformed
     */
    public static Credentials decodePlain(String base64Payload) {
        if (base64Payload == null || base64Payload.isBlank()) {
            throw new IllegalArgumentException("SASL PLAIN payload is empty");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Payload.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SASL PLAIN payload is not valid Base64");
        }

        // Format: [authzid NUL] authcid NUL passwd
        // We split on NUL bytes
        int firstNull = indexOf(decoded, (byte) 0, 0);
        if (firstNull == -1) {
            throw new IllegalArgumentException("SASL PLAIN: missing first NUL separator");
        }

        int secondNull = indexOf(decoded, (byte) 0, firstNull + 1);
        if (secondNull == -1) {
            throw new IllegalArgumentException("SASL PLAIN: missing second NUL separator");
        }

        // authzid is ignored (we use authcid as the identity)
        String username = new String(decoded, firstNull + 1, secondNull - firstNull - 1,
                StandardCharsets.UTF_8);
        String password = new String(decoded, secondNull + 1, decoded.length - secondNull - 1,
                StandardCharsets.UTF_8);

        if (username.isEmpty()) {
            throw new IllegalArgumentException("SASL PLAIN: empty username");
        }
        if (password.isEmpty()) {
            throw new IllegalArgumentException("SASL PLAIN: empty password");
        }

        return new Credentials(username, password);
    }

    public static Optional<String> decodeFirebaseAuthToken(String base64Payload){

        if (base64Payload == null || base64Payload.isBlank()) {
            throw new IllegalArgumentException("SASL PLAIN payload is empty");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Payload.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SASL PLAIN payload is not valid Base64");
        }

        FirebaseToken validatedToken;

        try{
            validatedToken = FirebaseAuth.getInstance().verifyIdTokenAsync(new String(decoded)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("FirebaseAuthError: "+e.getMessage());
        }

        if(validatedToken == null){
            throw new IllegalStateException("FirebaseAuth Validate token is Empty");
        }
        String uid = validatedToken.getUid();
        System.out.println("Current user id is: "+uid);
        return Optional.of(uid);
    }

    private static int indexOf(byte[] array, byte target, int from) {
        for (int i = from; i < array.length; i++) {
            if (array[i] == target) return i;
        }
        return -1;
    }
}