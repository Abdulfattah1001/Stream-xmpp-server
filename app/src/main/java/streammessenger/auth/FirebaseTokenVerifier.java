package streammessenger.auth;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Verifies Firebase ID tokens WITHOUT the Firebase Admin SDK.
 * <p>
 * Firebase ID tokens are standard JWTs signed with RS256.
 * Google publishes its public keys at a well-known URL.
 * We fetch those keys, cache them, and verify tokens locally.
 * <p>
 * This means:
 *  - No Firebase Admin SDK dependency
 *  - No Google service account JSON file needed
 *  - Pure Java JWT verification
 *  - Keys are cached and refreshed automatically
 * <p>
 * Key refresh URL:
 *   https://www.googleapis.com/robot/v1/metadata/x509/
 *   securetoken@system.gserviceaccount.com
 */
public final class FirebaseTokenVerifier {

    private static final Logger logger =
            Logger.getLogger(FirebaseTokenVerifier.class.getName());

    private static final String KEYS_URL =
        "https://www.googleapis.com/robot/v1/metadata/x509/" +
        "securetoken%40system.gserviceaccount.com";

    private static final String FIREBASE_ISS_PREFIX =
        "https://securetoken.google.com/";

    private final String projectId;

    // Cached public keys: kid → PublicKey
    private final ConcurrentHashMap<String, PublicKey> keyCache =
            new ConcurrentHashMap<>();

    // When to refresh keys (from Cache-Control header)
    private volatile long keyCacheExpiresAt = 0;

    private final ScheduledExecutorService keyRefresher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "firebase-key-refresher");
                t.setDaemon(true);
                return t;
            });

    public FirebaseTokenVerifier(String projectId) {
        this.projectId = projectId;
        // Load keys immediately on startup
        refreshKeys();
        // Refresh every 6 hours as a safety net
        keyRefresher.scheduleAtFixedRate(
                this::refreshKeys, 6, 6, TimeUnit.HOURS);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Verifies a Firebase ID token.
     *
     * @param idToken The raw JWT string from Firebase
     * @return Verified token claims
     * @throws InvalidTokenException if the token is invalid or expired
     */
    public VerifiedToken verify(String idToken) throws InvalidTokenException {
        if (idToken == null || idToken.isBlank()) {
            throw new InvalidTokenException("Token is null or empty");
        }

        // Split JWT: header.payload.signature
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("Invalid JWT format");
        }

        // 1. Decode header and payload
        String headerJson  = decodeBase64Url(parts[0]);
        String payloadJson = decodeBase64Url(parts[1]);

        // 2. Extract claims
        String algorithm = extractJsonString(headerJson, "alg");
        String kid       = extractJsonString(headerJson, "kid");
        String issuer    = extractJsonString(payloadJson, "iss");
        String audience  = extractJsonString(payloadJson, "aud");
        String subject   = extractJsonString(payloadJson, "sub");
        String phoneNum  = extractJsonString(payloadJson, "phone_number");
        long expiry      = extractJsonLong(payloadJson, "exp");
        long issuedAt    = extractJsonLong(payloadJson, "iat");

        // 3. Validate claims
        if (!"RS256".equals(algorithm)) {
            throw new InvalidTokenException(
                "Unsupported algorithm: " + algorithm);
        }

        if (kid == null) {
            throw new InvalidTokenException("Missing kid in header");
        }

        String expectedIssuer = FIREBASE_ISS_PREFIX + projectId;
        if (!expectedIssuer.equals(issuer)) {
            throw new InvalidTokenException(
                "Invalid issuer: " + issuer);
        }

        if (!projectId.equals(audience)) {
            throw new InvalidTokenException(
                "Invalid audience: " + audience);
        }

        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Missing subject (uid)");
        }

        long now = System.currentTimeMillis() / 1000;
        if (expiry < now) {
            throw new InvalidTokenException("Token has expired");
        }

        if (issuedAt > now + 300) { // 5 minute clock skew tolerance
            throw new InvalidTokenException("Token issued in the future");
        }

        // 4. Verify signature
        PublicKey publicKey = getPublicKey(kid);
        verifySignature(parts[0] + "." + parts[1], parts[2], publicKey);

        return new VerifiedToken(subject, phoneNum, issuer, audience, expiry);
    }

    // =========================================================================
    // Key management
    // =========================================================================

    private PublicKey getPublicKey(String kid) throws InvalidTokenException {
        // Refresh if cache is expired
        if (System.currentTimeMillis() > keyCacheExpiresAt) {
            refreshKeys();
        }

        PublicKey key = keyCache.get(kid);
        if (key == null) {
            // Try one more refresh in case keys rotated
            refreshKeys();
            key = keyCache.get(kid);
        }

        if (key == null) {
            throw new InvalidTokenException(
                "Unknown key id: " + kid);
        }

        return key;
    }

    private void refreshKeys() {
        try {
            logger.fine("Refreshing Firebase public keys");

            URL url = new URL(KEYS_URL);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            int status = conn.getResponseCode();
            if (status != 200) {
                logger.warning("Failed to fetch Firebase keys: HTTP " + status);
                return;
            }

            // Parse Cache-Control for expiry
            String cacheControl = conn.getHeaderField("Cache-Control");
            long maxAge = parseMaxAge(cacheControl);
            keyCacheExpiresAt = System.currentTimeMillis() + (maxAge * 1000);

            // Read response body (JSON map of kid → PEM certificate)
            String body;
            try (InputStream is = conn.getInputStream()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // Parse keys and cache them
            Map<String, String> certificates = parseCertMap(body);
            keyCache.clear();

            for (Map.Entry<String, String> entry : certificates.entrySet()) {
                try {
                    PublicKey key = parseCertificate(entry.getValue());
                    keyCache.put(entry.getKey(), key);
                } catch (Exception e) {
                    logger.warning("Failed to parse key "
                            + entry.getKey() + ": " + e.getMessage());
                }
            }

            logger.info("Firebase public keys refreshed: "
                    + keyCache.size() + " keys cached");

        } catch (Exception e) {
            logger.warning("Key refresh failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Signature verification
    // =========================================================================

    private void verifySignature(String headerPayload, String signatureB64,
                                  PublicKey publicKey)
            throws InvalidTokenException {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(headerPayload.getBytes(StandardCharsets.US_ASCII));

            byte[] signatureBytes = Base64.getUrlDecoder()
                    .decode(signatureB64);

            if (!sig.verify(signatureBytes)) {
                throw new InvalidTokenException("Signature verification failed");
            }
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidTokenException(
                "Signature error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Parsing helpers
    // =========================================================================

    private PublicKey parseCertificate(String pem) throws Exception {
        // PEM cert → DER bytes → PublicKey
        String stripped = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");

        byte[] derBytes = Base64.getDecoder().decode(stripped);

        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert = cf.generateCertificate(
                new ByteArrayInputStream(derBytes));

        return cert.getPublicKey();
    }

    private Map<String, String> parseCertMap(String json) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        // Simple JSON key-value parser for the cert map
        // Format: {"kid1": "-----BEGIN CERT-----\n...\n-----END CERT-----\n", ...}
        int pos = 1;
        while (pos < json.length()) {
            int keyStart = json.indexOf("\"", pos);
            if (keyStart == -1) break;
            int keyEnd = json.indexOf("\"", keyStart + 1);
            if (keyEnd == -1) break;

            String key = json.substring(keyStart + 1, keyEnd);

            int valStart = json.indexOf("\"", keyEnd + 1);
            if (valStart == -1) break;

            // Find the closing quote of the value
            // The PEM cert contains \n sequences, not actual newlines
            int valEnd = valStart + 1;
            while (valEnd < json.length()) {
                char c = json.charAt(valEnd);
                if (c == '"' && json.charAt(valEnd - 1) != '\\') break;
                valEnd++;
            }

            String value = json.substring(valStart + 1, valEnd)
                    .replace("\\n", "\n");
            result.put(key, value);
            pos = valEnd + 1;
        }
        return result;
    }

    private String decodeBase64Url(String encoded) {
        try {
            // Add padding if needed
            int padding = 4 - encoded.length() % 4;
            if (padding != 4) {
                encoded += "=".repeat(padding);
            }
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(":", idx + search.length());
        if (colonIdx == -1) return null;

        int valueStart = json.indexOf("\"", colonIdx + 1);
        if (valueStart == -1) return null;

        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd == -1) return null;

        return json.substring(valueStart + 1, valueEnd);
    }

    private long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return 0;

        int colonIdx = json.indexOf(":", idx + search.length());
        if (colonIdx == -1) return 0;

        int start = colonIdx + 1;
        while (start < json.length()
                && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        int end = start;
        while (end < json.length()
                && Character.isDigit(json.charAt(end))) {
            end++;
        }

        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseMaxAge(String cacheControl) {
        if (cacheControl == null) return 3600; // Default 1 hour
        for (String part : cacheControl.split(",")) {
            part = part.trim();
            if (part.startsWith("max-age=")) {
                try {
                    return Long.parseLong(part.substring(8).trim());
                } catch (NumberFormatException e) {
                    return 3600;
                }
            }
        }
        return 3600;
    }

    public void shutdown() {
        keyRefresher.shutdown();
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public record VerifiedToken(
            String uid,         // Firebase UID = subject claim
            String phoneNumber, // E.164 format from Firebase
            String issuer,
            String audience,
            long expiresAt
    ) {}

    public static class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}