package streammessenger.push;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;

/**
 * Push notification delivery via FCM (Android) and APNs (iOS).
 * <p>
 * Called when:
 *  - A message arrives for an offline user
 *  - A group message arrives and members are offline
 *  - A status is posted by a contact
 *  - A call is incoming
 * <p>
 * Notification payload is MINIMAL by design:
 *  - We do NOT send message content in push
 *  - We send only: "you have a new message from X"
 *  - Client fetches the actual content via XMPP on wake
 * <p>
 * Why minimal push:
 *  - Push passes through Google/Apple servers
 *  - Sending ciphertext in push is redundant (app will fetch anyway)
 *  - Sending plaintext in push violates E2E encryption
 */
public final class PushNotificationService {

    private static final Logger logger =
            Logger.getLogger(PushNotificationService.class.getName());

    // FCM HTTP v1 API endpoint
    private static final String FCM_URL =
            "https://fcm.googleapis.com/v1/projects/%s/messages:send";

    // APNs endpoint (production)
    private static final String APNS_URL =
            "https://api.push.apple.com/3/device/%s";

    // APNs endpoint (sandbox for dev)
    private static final String APNS_SANDBOX_URL =
            "https://api.sandbox.push.apple.com/3/device/%s";

    //TODO: This should be hidden
    private final String PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC251yyFa56j8Uu\nZBYCMd5WvyuMa2rPVj614Ynbk2tHj8+HW8tQ+ZaBgK4IrhO7v4p/oMDIS3+knKE0\n49jZaTy49Pp09GPIdcO7rB0VhObvAShcvlqUR/dYBD66TwJC3z1fcQiECzeJ+ClA\noygBdiLrf4n+hywl4Sk01Odt00K5VVYwEhDuSocbY+AuYUxJEVWJnXSKodTND66Y\njegQBDU8pBONvdSXjRx/09sFV034QfYMPYatvjiGm19D3sTUHFEvH1Hkod2H8u21\nu+/wV6gDXMhiOsq7Et9VJme+rBa9pqxXR8XkCjYkf5u1YWyrQ9PCvqfFEDyCjY/T\nPEhVYaRPAgMBAAECggEAVJSwKpBZM8c5bYcOIGy0P1Q/TLu91GyzKkPGjvpgsWKh\nGxzJbERHI9MAZ/YsHPXqE7Qggl9bgyGFcOJuvLdsQ7HSAnSjkidXYPmqJ3HioyLb\naewDEjAngxXPdjOkPY1jobexMvLG97ABT6lDjk11v4amp0QWF9xERHCyJvj7kZ1L\nIxbBsBezIn5E7aLagYZ4nD7U9CCSmAoyuO9jk4KNpgwpPXvRFpCGVirLCr5Xgog6\n164rOsgKTl1kCigh1PKOVV7siXd1rm6pkqpll9ZKSrwq68JTOpACTV/NNnwXPGSb\ni0mHZJ6tnXyFVt/bDe/2TVaDWl8gZku4p7QSBczjqQKBgQDhtV1DWqJssmyyi8bR\nQVX5DnQ8XgGyKcM81P1EdF0gT+Q42zAr/EMdCuBL3ZkCrvwYGiLl+w/y+YQmXtmP\njSm+0oBQbwkoSxF+3NdUWHUKK/jhQueVl/FrHTLMBIRyRuBTHgjyrghBeekr+ZEm\n7uAZ5ZF0tK4HZIadOAAwrju4WQKBgQDPc1zKgwRWr7kRYXHjHZWk5DaI1MayyAUF\nw2UmQlY88iyNcI94S3fF72YDxTwqWAchxZ0HkPzLaGhmJ0XwFSUQ0qy3TLAELAnd\nW2Q3BV0tfcwQ6z0RaEAwjOTiZ8F2Bss27OI0vk3b2PtYn5phhRqEjnKSxin4FXtg\ntboZXmcs5wKBgGL66f9Ti88nH8vcyD+T62PhFtAyWYQMFHZk4PxYG07EOk1EsgdY\nBQaDcoFSmHs4yYy4SX2ZcBEZov5Ash/lw9zO6z5asyVcZjvAFR4D/K+NQQNoF67e\nhxx2HYSipoKG2nEYxsvFzhEIqVyDgUgVkWlJ51PKuFa9mtrvaAXxIndhAoGAVI3Y\nzFIKeqq06/ijysZMMCE0eSEAu+363hZ+K9HuBHlQ33V5hLZ94xdopTDHDRtEDOfW\n0TavUtkDdF+difWUXf8AltWTCKBKhQazGhn9mIUln9/BzE6Jm0BSKlXP7KNoQMLc\nkFLguTL/f2fOLOFrpYvJ9zj98jgPSaPIbn6j3xECgYEAwSwOeCsj6oj0pGNyjT6M\n6KNZJtrI0V0auQHOHcJXylmt3lUyODRVxi6xN0I55L5H1MK11IQu8S3yKkGa/2lm\nQ6WAw4PbKpwEg1YFrDwq0Wnraz0UXXFehdD6aDp6s+FasP+AMRl8LHyhCPu2jVd1\ngxgU60W2n/XzBW3F5iByE0s=\n-----END PRIVATE KEY-----\n";

    //TODO: This should be hidden
    private final String CLIENT_EMAIL = "firebase-adminsdk-x7ial@stream-6fa32.iam.gserviceaccount.com";

    private final String fcmProjectId;
    //private final String fcmServiceAccountJson; // OAuth2 access token source
    private final String apnsBundleId;
    private final boolean isDev;
    private final DatabaseManager db;

    // Thread pool for async push delivery
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 20,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // FCM OAuth2 token cache
    private volatile String fcmAccessToken;
    private volatile long fcmTokenExpiresAt = 0;

    private static PushNotificationService instance = null;

    public PushNotificationService(String fcmProjectId,
                                    String fcmServiceAccountJson,
                                    String apnsBundleId,
                                    boolean isDev,
                                    DatabaseManager db) {
        this.fcmProjectId          = fcmProjectId;
        //this.fcmServiceAccountJson = fcmServiceAccountJson;
        this.apnsBundleId          = apnsBundleId;
        this.isDev                 = isDev;
        this.db                    = db;
    }

    public static PushNotificationService getInstance() {
        return instance;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Sends a "new message" push notification.
     * Called when a message is stored offline.
     */
    public void sendMessageNotification(String toUserId,
                                         String fromDisplayName,
                                         String messageType) {
        executor.execute(() -> {
            DatabaseManager.PushTarget target = db.getPushTarget(toUserId);
            if (target == null || target.pushToken() == null) return;

            String title = fromDisplayName;
            String body  = switch (messageType) {
                case "image"    -> "📷 Photo";
                case "video"    -> "🎥 Video";
                case "audio"    -> "🎤 Voice message";
                case "file"     -> "📎 File";
                case "location" -> "📍 Location";
                default          -> "New message";
            };

            sendPush(target, title, body, "message",
                    toUserId, null);
        });
    }

    /**
     * Sends a "missed call" push notification.
     */
    public void sendCallNotification(String toUserId,
                                      String callerDisplayName,
                                      String callType,
                                      String callId) {
        executor.execute(() -> {
            DatabaseManager.PushTarget target = db.getPushTarget(toUserId);
            if (target == null || target.pushToken() == null) return;

            String emoji = "video".equals(callType) ? "📹" : "📞";
            sendPush(target,
                    emoji + " Incoming " + callType + " call",
                    callerDisplayName,
                    "call",
                    toUserId, callId);
        });
    }

    public void sendPushCallNotification(String toUserId,
                                         String callerDisplayName,
                                         String callType,
                                         String callId){

        executor.execute(() -> {
            DatabaseManager.PushTarget target = db.getPushTarget(toUserId);
            if(target == null || target.pushToken() == null) return;

            String emoji = "video".equals(callType) ? "📹" : "📞";
            sendPush(target,
                    emoji + " Incoming " + callType + " call",
                    callerDisplayName,
                    "call",
                    toUserId, callId);
        });
    }

    /**
     * Sends a "new group message" push notification.
     */
    public void sendGroupMessageNotification(String toUserId,
                                              String groupName,
                                              String senderName,
                                              String messageType) {
        executor.execute(() -> {
            DatabaseManager.PushTarget target = db.getPushTarget(toUserId);
            if (target == null || target.pushToken() == null) return;

            String body = switch (messageType) {
                case "image" -> senderName + ": 📷 Photo";
                case "video" -> senderName + ": 🎥 Video";
                case "audio" -> senderName + ": 🎤 Voice message";
                default       -> senderName + ": New message";
            };

            sendPush(target, groupName, body,
                    "group_message", toUserId, null);
        });
    }

    /**
     * Sends a "new status" push notification to a follower.
     */
    public void sendStatusNotification(String toUserId,
                                        String authorName) {
        executor.execute(() -> {
            DatabaseManager.PushTarget target = db.getPushTarget(toUserId);
            if (target == null || target.pushToken() == null) return;

            sendPush(target,
                    authorName + " posted a status",
                    "Tap to view",
                    "status", toUserId, null);
        });
    }

    /**
     * Sends a "new blog post" push notification.
     */
    public void sendBlogNotification(String toUserId,
                                      String authorName,
                                      String blogTitle) {
        executor.execute(() -> {
            DatabaseManager.PushTarget target = db.getPushTarget(toUserId);
            if (target == null || target.pushToken() == null) return;

            sendPush(target,
                    authorName + " published an article",
                    blogTitle,
                    "blog", toUserId, null);
        });
    }

    // =========================================================================
    // Core delivery
    // =========================================================================

    private void sendPush(DatabaseManager.PushTarget target,
                           String title,
                           String body,
                           String type,
                           String toUserId,
                           String referenceId) {
        // Idempotency key prevents duplicate pushes
        String idempotencyKey = type + ":" + toUserId + ":"
                + System.currentTimeMillis() / 60_000; // 1-minute window

        boolean success = false;
        String errorCode = null;

        try {
            if ("android".equalsIgnoreCase(target.platform())) {
                success = sendFCM(target.pushToken(), title, body,
                        type, referenceId);
            } else if ("ios".equalsIgnoreCase(target.platform())) {
                success = sendAPNs(target.pushToken(), title, body,
                        type, referenceId);
            }
        } catch (Exception e) {
            errorCode = e.getMessage();
            logger.warning("Push failed for userId=" + toUserId + ": " + e.getMessage());
        }

        // Log the attempt
        db.logPushNotification(toUserId, target.pushToken(),
                target.platform(), type, referenceId,
                success, errorCode, idempotencyKey);

        if (!success && "TOKEN_EXPIRED".equals(errorCode)) {
            // Token is invalid - clear it so we stop trying
            db.clearPushToken(toUserId, target.pushToken());
        }
    }

    // =========================================================================
    // FCM (Android)
    // =========================================================================

    private boolean sendFCM(String token,
                              String title,
                              String body,
                              String type,
                              String referenceId) throws IOException {

        String accessToken = getFCMAccessToken();
        String url = String.format(FCM_URL, fcmProjectId);

        // Build FCM v1 payload
        String payload = String.format("""
            {
              "message": {
                "token": "%s",
                "notification": {
                  "title": "%s",
                  "body": "%s"
                },
                "data": {
                  "type": "%s",
                  "reference_id": "%s"
                },
                "android": {
                  "priority": "HIGH",
                  "notification": {
                    "channel_id": "messages",
                    "sound": "default"
                  }
                }
              }
            }
            """,
                escapeJson(token),
                escapeJson(title),
                escapeJson(body),
                type,
                referenceId != null ? referenceId : ""
        );

        return postJson(url, payload,
                "Authorization", "Bearer " + accessToken);
    }

    /**
     * Gets or refreshes the FCM OAuth2 access token.
     * FCM v1 requires OAuth2 - not the legacy server key.
     * <p>
     * In production: use a proper OAuth2 library or Google Auth Library.
     * Here: simplified JWT-based token generation.
     */
    private synchronized String getFCMAccessToken() throws IOException {
        if (fcmAccessToken != null
                && System.currentTimeMillis() < fcmTokenExpiresAt - 60_000) {
            return fcmAccessToken;
        }

        // Build JWT for Google OAuth2
        // In production: parse the service account JSON properly
        // This is a simplified placeholder
        String jwt = buildServiceAccountJWT();

        String tokenUrl = "https://oauth2.googleapis.com/token";
        String payload = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3A"
                + "grant-type%3Ajwt-bearer&assertion=" + jwt;

        // POST to get access token
        URL url = new URL(tokenUrl);
        HttpsURLConnection conn =
                (HttpsURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        String response;
        try (InputStream is = conn.getInputStream()) {
            response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Parse access_token from response
        String token = extractJsonValue(response, "access_token");
        String expiresIn = extractJsonValue(response, "expires_in");

        fcmAccessToken = token;
        fcmTokenExpiresAt = System.currentTimeMillis()
                + (Long.parseLong(expiresIn) * 1000);

        return fcmAccessToken;
    }

    @Deprecated
    private String buildServiceAccountJwt() {
        // In production: implement proper JWT signing with RS256
        // using the private key from the service account JSON
        // This placeholder shows the structure
        long now = System.currentTimeMillis() / 1000;

        String header = java.util.Base64.getUrlEncoder()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                        .getBytes());

        String claims = java.util.Base64.getUrlEncoder()
                .encodeToString(String.format("""
                    {
                      "iss": "firebase-adminsdk@%s.iam.gserviceaccount.com",
                      "scope": "https://www.googleapis.com/auth/firebase.messaging",
                      "aud": "https://oauth2.googleapis.com/token",
                      "iat": %d,
                      "exp": %d
                    }
                    """, CLIENT_EMAIL, now, now + 3600)
                        .getBytes());

        // TODO: Sign header.claims with RSA private key from service account
        PrivateKey privateKey = null;
        try{
            privateKey = loadPrivateKey( PRIVATE_KEY);
        } catch (Exception e) {
            logger.info("Exception occurred: "+e.getMessage());
        }
        String signature = rsaSign(header + "." + claims, privateKey);
        return header + "." + claims + "." + signature;

        /*throw new UnsupportedOperationException(
            "Implement JWT signing with service account private key");*/
    }

    private String buildServiceAccountJWT() {

        long now = System.currentTimeMillis() / 1000;

        String headerJson =
                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";

        String claimsJson = String.format(
                "{\"iss\":\"%s\","
                        + "\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\","
                        + "\"aud\":\"https://oauth2.googleapis.com/token\","
                        + "\"iat\":%d,"
                        + "\"exp\":%d}",
                CLIENT_EMAIL,
                now,
                now + 3600
        );

        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        headerJson.getBytes(StandardCharsets.UTF_8));

        String claims = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        claimsJson.getBytes(StandardCharsets.UTF_8));

        String unsignedJwt = header + "." + claims;

        PrivateKey privateKey;

        try {
            privateKey = loadPrivateKey(PRIVATE_KEY);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String signature =
                rsaSign(unsignedJwt, privateKey);

        return unsignedJwt + "." + signature;
    }

    private PrivateKey loadPrivateKey(String pem)
            throws Exception {

        String content = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replace("\n", "");

        byte[] decoded =
                Base64.getDecoder().decode(content);

        PKCS8EncodedKeySpec spec =
                new PKCS8EncodedKeySpec(decoded);

        KeyFactory keyFactory =
                KeyFactory.getInstance("RSA");

        return keyFactory.generatePrivate(spec);
    }

    private String rsaSign(String data, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));

            byte[] signed = signature.sign();

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(signed);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // APNs (iOS)
    // =========================================================================

    private boolean sendAPNs(String deviceToken,
                               String title,
                               String body,
                               String type,
                               String referenceId) throws IOException {

        String url = String.format(
                isDev ? APNS_SANDBOX_URL : APNS_URL, deviceToken);

        String payload = String.format("""
            {
              "aps": {
                "alert": {
                  "title": "%s",
                  "body": "%s"
                },
                "sound": "default",
                "badge": 1,
                "mutable-content": 1
              },
              "type": "%s",
              "reference_id": "%s"
            }
            """,
                escapeJson(title),
                escapeJson(body),
                type,
                referenceId != null ? referenceId : ""
        );

        // APNs uses HTTP/2 with JWT authentication
        // Requires the APNs auth key (.p8 file) from Apple Developer
        // In production: use a proper HTTP/2 client
        // Java 11+ HttpClient supports HTTP/2

        java.net.http.HttpClient client =
                java.net.http.HttpClient.newHttpClient();

        java.net.http.HttpRequest request =
                java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("apns-topic", apnsBundleId)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("authorization", "bearer " + getAPNsToken())
                .header("content-type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers
                        .ofString(payload))
                .build();

        try {
            java.net.http.HttpResponse<String> response =
                    client.send(request,
                            java.net.http.HttpResponse.BodyHandlers
                                    .ofString());

            int status = response.statusCode();
            if (status == 200) return true;

            logger.warning("APNs error " + status + ": " + response.body());
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String getAPNsToken() {
        // Build JWT signed with APNs auth key (.p8)
        // In production: implement with the auth key from Apple
        throw new UnsupportedOperationException(
            "Implement APNs JWT with .p8 auth key");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean postJson(String urlStr, String payload,
                              String authHeader, String authValue)
            throws IOException {

        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty(authHeader, authValue);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();

        if (status >= 200 && status < 300) return true;

        // Read error body
        String error = "";
        try (InputStream es = conn.getErrorStream()) {
            if (es != null) {
                error = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        logger.warning("Push HTTP " + status + ": " + error);

        if (error.contains("UNREGISTERED") || error.contains("InvalidRegistration")) {
            throw new IOException("TOKEN_EXPIRED");
        }

        return false;
    }

    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) {
            // Try without quotes (for numbers)
            search = "\"" + key + "\":";
            idx = json.indexOf(search);
            if (idx == -1) return "0";
            int start = idx + search.length();
            int end   = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return json.substring(start, end).trim();
        }
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public void shutdown() {
        executor.shutdown();
    }
}