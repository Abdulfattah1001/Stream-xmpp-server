package streammessenger.api;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import streammessenger.auth.FirebaseTokenVerifier;
import streammessenger.auth.SessionTokenService;
import streammessenger.db.DatabaseManager;

/**
 * Lightweight HTTP API for registration and session token issuance.
 * Note that the registration can also be bypassed by using the
 * Nodejs Server which serves as an utility Server to this one,
 * it handles profile picture image upload, OTP generation e.t.c
 * <p>
 * Endpoints:
 *   POST /auth/register  → Register new user with Firebase token
 *   POST /auth/login     → Login returning user, get session token
 *   POST /auth/logout    → Revoke session token
 *   POST /auth/discover  → Find which contacts are registered
 *   POST /auth/links     -> Generate, revoke or reset a link for a group
 * <p>
 * Uses Java's built-in HttpServer (no framework needed).
 * In production: Nginx is put in front for TLS termination.
 */
public final class AuthController {
    private static final Logger logger = Logger.getLogger(AuthController.class.getName());

    private final HttpServer httpServer;
    private final DatabaseManager db;
    private final FirebaseTokenVerifier firebaseVerifier;
    private final SessionTokenService tokenService;

    public AuthController(int port,
                          DatabaseManager db,
                          FirebaseTokenVerifier firebaseVerifier,
                          SessionTokenService tokenService) throws IOException {
        this.db               = db;
        this.firebaseVerifier = firebaseVerifier;
        this.tokenService     = tokenService;

        this.httpServer = HttpServer.create(new InetSocketAddress(port), 50);

        // Register endpoints
        httpServer.createContext("/auth/register", this::handleRegister);
        httpServer.createContext("/auth/login",    this::handleLogin);
        httpServer.createContext("/auth/logout",   this::handleLogout);
        httpServer.createContext("/auth/discover", this::handleDiscover);
        httpServer.createContext("/health",        this::handleHealth);

        httpServer.setExecutor(
            java.util.concurrent.Executors.newFixedThreadPool(10));
    }

    public void start() {
        httpServer.start();
        logger.info("Auth API started on port " + httpServer.getAddress().getPort());
    }

    public void stop() {
        httpServer.stop(5);
    }

    // =========================================================================
    // POST /auth/register
    // =========================================================================

    /**
     * Registers a new user.
     * <p>
     * Request body (JSON):
     * <p>
     * <pre>{
     *   "firebase_token": "eyJhbGci...",   // Firebase ID token
     *   "phone_number":   "+2348012345678",
     *   "display_name":   "Alice",
     *   "device_label":   "Samsung Galaxy S24",
     *   "push_token":     "fcm_token_here",
     *   "platform":       "android",
     *   "app_version":    "1.0.0"
     * }</pre>
     * <p>
     * Response (JSON):
     * <p>
     * <pre>{
     *   "session_token": "st_abc123...",
     *   "jid":           "u_7f3a9b2c@omnyrex.com",
     *   "user_id":       "u_7f3a9b2c",
     *   "expires_at":    "2024-12-31T00:00:00Z"
     * }</pre>
     */
    private void handleRegister(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }


        try {
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String firebaseToken = req.getString("firebase_token");
            String phoneNumber   = req.getString("phone_number");
            String displayName   = req.getString("display_name");
            String deviceLabel   = req.getString("device_label");
            String pushToken     = req.getString("push_token");
            String platform      = req.getString("platform");
            String appVersion    = req.getString("app_version");

            if (firebaseToken == null || phoneNumber == null) {
                sendError(exchange, 400, "firebase_token and phone_number are required");
                return;
            }

            // 1. Verify Firebase token
            FirebaseTokenVerifier.VerifiedToken verified = firebaseVerifier.verify(firebaseToken);

            // 2. Check phone number matches Firebase claim
            if (!phoneNumber.equals(verified.phoneNumber())) {
                sendError(exchange, 401, "Phone number mismatch");
                return;
            }

            // 3. Register or update user in DB
            DatabaseManager.UserRecord user = db.registerUser(
                    verified.uid(), //The assigned uid from the firebase auth service
                    phoneNumber, //The phone number the user is using at the time
                    displayName //Null at registering time
            );

            // 4. Issue session token
            SessionTokenService.IssuedToken token = tokenService.issueToken(
                    user.userId(),
                    deviceLabel,
                    pushToken,
                    platform,
                    appVersion,
                    getClientIp(exchange)
            );

            // 5. Log registration
            db.insertAuditLog("user_registered", user.userId(),
                    "{\"phone_hash\":\"" + hashPhone(phoneNumber) + "\"}",
                    getClientIp(exchange));

            // 6. Respond
            sendJson(exchange, 201, String.format("""
                {
                    "session_token": "%s",
                    "jid":           "%s",
                    "user_id":       "%s",
                    "display_name":  "%s"
                }
                """,
                token.rawToken(),
                user.jid(),
                user.userId(),
                user.displayName() != null ? user.displayName() : ""
            ));

        } catch (FirebaseTokenVerifier.InvalidTokenException e) {
            logger.warning("Invalid Firebase token: " + e.getMessage());
            sendError(exchange, 401, "Invalid or expired Firebase token");
        } catch (Exception e) {
            logger.severe("Register error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // POST /auth/login
    // =========================================================================

    /**
     * Logs in a returning user.
     * Called when Firebase silently refreshes the ID token.
     * <p>
     * Request:
     * {
     *   "firebase_token": "eyJhbGci...",
     *   "device_label":   "Samsung Galaxy S24",
     *   "push_token":     "new_fcm_token",
     *   "platform":       "android",
     *   "app_version":    "1.0.1"
     * }
     * <p>
     * Response:
     * {
     *   "session_token": "st_xyz789...",
     *   "jid":           "u_7f3a9b2c@omnyrex.com",
     *   "user_id":       "u_7f3a9b2c",
     *   "expires_at":    "2025-01-31T00:00:00Z"
     * }
     */
    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String firebaseToken = req.getString("firebase_token");
            String deviceLabel   = req.getString("device_label");
            String pushToken     = req.getString("push_token");
            String platform      = req.getString("platform");
            String appVersion    = req.getString("app_version");

            if (firebaseToken == null) {
                sendError(exchange, 400, "firebase_token is required");
                return;
            }

            // 1. Verify Firebase token
            FirebaseTokenVerifier.VerifiedToken verified =
                    firebaseVerifier.verify(firebaseToken);

            // 2. Look up user by Firebase UID
            DatabaseManager.UserRecord user =
                    db.getUserByFirebaseUid(verified.uid());

            if (user == null) {
                // Never registered - tell client to register first
                sendError(exchange, 404,
                    "User not found. Please register first.");
                return;
            }

            if (!user.active()) {
                sendError(exchange, 403, "Account is disabled");
                return;
            }

            // 3. Update push token if changed
            if (pushToken != null) {
                db.updatePushToken(user.userId(), pushToken, platform);
            }

            // 4. Issue new session token
            SessionTokenService.IssuedToken token = tokenService.issueToken(
                    user.userId(),
                    deviceLabel,
                    pushToken,
                    platform,
                    appVersion,
                    getClientIp(exchange)
            );

            db.insertAuditLog("user_login", user.userId(), null,
                    getClientIp(exchange));

            sendJson(exchange, 200, String.format("""
                {
                    "session_token": "%s",
                    "jid":           "%s",
                    "user_id":       "%s",
                    "display_name":  "%s",
                }
                """,
                token.rawToken(),
                user.jid(),
                user.userId(),
                user.displayName() != null ? user.displayName() : ""
            ));

        } catch (FirebaseTokenVerifier.InvalidTokenException e) {
            sendError(exchange, 401, "Invalid or expired Firebase token");
        } catch (Exception e) {
            logger.severe("Login error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // POST /auth/logout
    // =========================================================================

    /**
     * Revokes a session token.
     * <p>
     * Request header: Authorization: Bearer st_abc123...
     * Request body (optional):
     * {
     *   "all_devices": true   // revoke ALL sessions for this user
     * }
     */
    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String rawToken = extractBearerToken(exchange);
            if (rawToken == null) {
                sendError(exchange, 401, "Authorization header required");
                return;
            }

            String body = readBody(exchange);
            boolean allDevices = body.contains("\"all_devices\":true");

            // Validate token
            SessionTokenService.ValidatedToken validated =
                    tokenService.validate(rawToken);

            if (validated == null) {
                sendError(exchange, 401, "Invalid or expired session token");
                return;
            }

            if (allDevices) {
                db.revokeAllSessionTokens(validated.userId(), "user_logout_all");
            } else {
                db.revokeSessionToken(rawToken, "user_logout");
            }

            db.insertAuditLog("user_logout", validated.userId(),
                    "{\"all_devices\":" + allDevices + "}",
                    getClientIp(exchange));

            sendJson(exchange, 200, "{\"status\":\"logged_out\"}");

        } catch (Exception e) {
            logger.severe("Logout error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // POST /auth/discover
    // =========================================================================

    /**
     * Contact discovery - finds which phone numbers are registered.
     * <p>
     * App sends hashed phone numbers from the user's phonebook.
     * Server returns which ones are registered (with their JIDs).
     * Raw phone numbers are NEVER sent to the server.
     * <p>
     * Request:
     * {
     *   "phone_hashes": [
     *     "sha256_of_+2348012345678",
     *     "sha256_of_+2348098765432",
     *     ...
     *   ]
     * }
     * <p>
     * Response:
     * {
     *   "matches": [
     *     {
     *       "phone_hash":    "sha256_of_+2348012345678",
     *       "jid":           "u_7f3a9b2c@omnyrex.com",
     *       "display_name":  "Alice"
     *     }
     *   ]
     * }
     */
    private void handleDiscover(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String rawToken = extractBearerToken(exchange);
            if (rawToken == null) {
                sendError(exchange, 401, "Authorization header required");
                return;
            }

            SessionTokenService.ValidatedToken validated = tokenService.validate(rawToken);
            if (validated == null) {
                sendError(exchange, 401, "Invalid session token");
                return;
            }

            String body = readBody(exchange);

            // Parse hash array from JSON
            java.util.List<String> hashes = parseHashArray(body);

            if (hashes.isEmpty()) {
                sendJson(exchange, 200, "{\"matches\":[]}");
                return;
            }

            // Enforce limit to prevent bulk scraping
            if (hashes.size() > 1000) {
                sendError(exchange, 400,
                    "Maximum 1000 hashes per request");
                return;
            }

            // Look up matches
            java.util.List<DatabaseManager.ContactMatch> matches =
                    db.discoverContacts(hashes, validated.userId());

            // Build response
            StringBuilder json = new StringBuilder("{\"matches\":[");
            for (int i = 0; i < matches.size(); i++) {
                DatabaseManager.ContactMatch m = matches.get(i);
                if (i > 0) json.append(",");
                json.append(String.format(
                    "{\"phone_hash\":\"%s\",\"jid\":\"%s\",\"display_name\":\"%s\"}",
                    m.phoneHash(),
                    m.jid(),
                    m.displayName() != null ? m.displayName() : ""
                ));
            }
            json.append("]}");

            sendJson(exchange, 200, json.toString());

        } catch (Exception e) {
            logger.severe("Discover error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // GET /health
    // =========================================================================

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private void sendJson(HttpExchange ex, int status, String json)
            throws IOException {
        byte[] bytes = json.trim().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange ex, int status, String message)
            throws IOException {
        sendJson(ex, status,
            "{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean isPost(HttpExchange ex) {
        return "POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    private String extractBearerToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return auth.substring(7).trim();
    }

    private String getClientIp(HttpExchange ex) {
        // Check X-Forwarded-For first (set by Nginx)
        String forwarded = ex.getRequestHeaders()
                .getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private java.util.List<String> parseHashArray(String json) {
        java.util.List<String> hashes = new java.util.ArrayList<>();
        int start = json.indexOf("[");
        int end   = json.lastIndexOf("]");
        if (start == -1 || end == -1) return hashes;

        String inner = json.substring(start + 1, end);
        for (String part : inner.split(",")) {
            String hash = part.trim()
                    .replace("\"", "")
                    .replace("'", "")
                    .trim();
            if (!hash.isEmpty()) hashes.add(hash);
        }
        return hashes;
    }

    private String hashPhone(String phone) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                    phone.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}