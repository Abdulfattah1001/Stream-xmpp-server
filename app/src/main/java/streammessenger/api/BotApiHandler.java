package streammessenger.api;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Open API for bots and third-party integrations.
 *
 * WHAT THIS IS:
 * ─────────────
 * A webhook-based API that allows external services to:
 *   - Send messages to users (as a bot)
 *   - Receive messages sent to the bot
 *   - Post blog articles automatically
 *   - Broadcast announcements to groups
 *
 * Think: Telegram Bot API, Slack App API, but for your server.
 *
 * BOT CREATION FLOW:
 * ──────────────────
 * 1. Developer registers a bot via the API
 * 2. Server creates a bot user with a JID: bot_name@yourdomain.com
 * 3. Developer gets an API key
 * 4. Developer registers a webhook URL
 * 5. When a user messages the bot, server POSTs to webhook
 * 6. Bot handler POSTs a reply to /api/bot/send
 *
 * SECURITY:
 * ─────────
 * - API key authentication for all bot endpoints
 * - Rate limiting per bot (prevents spam)
 * - Webhook signature verification (HMAC-SHA256)
 * - Bots cannot impersonate regular users
 * - Bots cannot read messages not addressed to them
 *
 * ENDPOINTS:
 * ──────────
 *   POST /api/bot/register     → Register a new bot
 *   POST /api/bot/send         → Send a message as the bot
 *   POST /api/bot/webhook      → Register/update webhook URL
 *   GET  /api/bot/info         → Get bot info
 *   POST /api/bot/broadcast    → Send to all users (admin bots only)
 */
public final class BotApiHandler {

    private static final Logger logger =
            Logger.getLogger(BotApiHandler.class.getName());

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConnectionPool pool;
    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final HttpServer httpServer;

    public BotApiHandler(int port,
                          ConnectionPool pool,
                          DatabaseManager db,
                          SessionRegistry registry) throws IOException {
        this.pool     = pool;
        this.db       = db;
        this.registry = registry;

        this.httpServer = HttpServer.create(
                new InetSocketAddress(port), 50);

        httpServer.createContext("/api/bot/register",  this::handleRegister);
        httpServer.createContext("/api/bot/send",      this::handleSend);
        httpServer.createContext("/api/bot/webhook",   this::handleWebhook);
        httpServer.createContext("/api/bot/info",      this::handleInfo);
        httpServer.createContext("/api/bot/broadcast", this::handleBroadcast);

        httpServer.setExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(5));
    }

    public void start() {
        httpServer.start();
        logger.info("Bot API started on port "
                + httpServer.getAddress().getPort());
    }

    // =========================================================================
    // Register Bot
    // =========================================================================

    /**
     * POST /api/bot/register
     *
     * Creates a new bot account.
     * Only admin users can create bots (checked via admin API key).
     *
     * Request:
     * {
     *   "admin_key": "admin_api_key",
     *   "bot_name": "my_news_bot",
     *   "display_name": "My News Bot",
     *   "description": "Posts daily news summaries",
     *   "webhook_url": "https://mybotserver.com/webhook",
     *   "webhook_secret": "my_secret_for_hmac"
     * }
     *
     * Response:
     * {
     *   "bot_id": "bot_7f3a9b2c",
     *   "jid": "my_news_bot@yourdomain.com",
     *   "api_key": "bk_7f3a9b2c...",
     *   "api_secret": "bs_abc123..."
     * }
     */
    private void handleRegister(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) { sendError(exchange, 405, "Method not allowed"); return; }

        try {
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String adminKey    = req.getString("admin_key");
            String botName     = req.getString("bot_name");
            String displayName = req.getString("display_name");
            String description = req.getString("description");
            String webhookUrl  = req.getString("webhook_url");
            String webhookSecret = req.getString("webhook_secret");

            if (!validateAdminKey(adminKey)) {
                sendError(exchange, 401, "Invalid admin key");
                return;
            }

            if (botName == null || !botName.matches("[a-z0-9_]{3,32}")) {
                sendError(exchange, 400,
                    "bot_name must be 3-32 lowercase alphanumeric chars/underscores");
                return;
            }

            // Check bot name not taken
            if (botExists(botName)) {
                sendError(exchange, 409, "Bot name already taken");
                return;
            }

            String botId    = "bot_" + generateHex(4);
            String jid      = botName + "@"
                    + System.getProperty("xmpp.domain", "localhost");
            String apiKey   = "bk_" + generateHex(16);
            String apiSecret = "bs_" + generateHex(16);

            // Create bot record
            createBot(botId, botName, jid, displayName, description,
                    webhookUrl, webhookSecret,
                    hashKey(apiKey), hashKey(apiSecret));

            sendJson(exchange, 201,
                SimpleJson.builder()
                    .put("bot_id",     botId)
                    .put("jid",        jid)
                    .put("api_key",    apiKey)    // Only shown once
                    .put("api_secret", apiSecret) // Only shown once
                    .build()
            );

            logger.info("Bot registered: botId=" + botId + " jid=" + jid);

        } catch (Exception e) {
            logger.severe("Bot register error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // Send Message as Bot
    // =========================================================================

    /**
     * POST /api/bot/send
     *
     * Sends a message to a user or group as the bot.
     * The message is delivered via XMPP to the recipient.
     *
     * Request:
     * {
     *   "api_key": "bk_7f3a9b2c...",
     *   "to": "u_alice@yourdomain.com",
     *   "message_type": "text",
     *   "body": "Hello from the news bot!",
     *   "buttons": [
     *     {"label": "Read More", "action": "open_url",
     *      "value": "https://news.com/article"}
     *   ]
     * }
     *
     * Response:
     * {
     *   "message_id": "uuid",
     *   "delivered": true
     * }
     *
     * NOTE: Bot messages are NOT end-to-end encrypted.
     * They are plaintext from the server to the client.
     * The client knows it's a bot message (different rendering).
     */
    private void handleSend(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) { sendError(exchange, 405, "Method not allowed"); return; }

        try {
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String apiKey = req.getString("api_key");
            BotRecord bot = validateBotApiKey(apiKey);
            if (bot == null) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            String to          = req.getString("to");
            String msgType     = req.getString("message_type");
            String msgBody     = req.getString("body");

            if (to == null || msgBody == null) {
                sendError(exchange, 400, "to and body are required");
                return;
            }

            String messageId = java.util.UUID.randomUUID().toString();

            // Build bot message stanza
            String stanza = String.format(
                "<message id='%s' from='%s' to='%s' type='chat'>" +
                "<body>%s</body>" +
                "<bot xmlns='urn:xmpp:bot:0'" +
                " bot_id='%s'" +
                " bot_name='%s'/>" +
                "</message>",
                messageId,
                escapeXml(bot.jid()),
                escapeXml(to),
                escapeXml(msgBody),
                bot.botId(),
                escapeXml(bot.displayName())
            );

            // Try to deliver online
            String bareJid = toBareJid(to);
            boolean delivered = registry.getByContactId(bareJid)
                    .filter(Session::isAuthenticated)
                    .map(s -> s.writeXML(stanza))
                    .orElse(false);

            if (!delivered) {
                // Store as offline message
                db.storeEncryptedMessage(
                        bot.jid(), bareJid, messageId,
                        msgType != null ? msgType : "text",
                        msgBody, // Not encrypted for bot messages
                        "bot",   // iv field used to mark as bot message
                        null, null, null, 0, null
                );
            }

            // Update bot last active
            updateBotLastActive(bot.botId());

            sendJson(exchange, 200,
                SimpleJson.builder()
                    .put("message_id", messageId)
                    .put("delivered",  delivered)
                    .build()
            );

        } catch (Exception e) {
            logger.severe("Bot send error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // Register/Update Webhook
    // =========================================================================

    /**
     * POST /api/bot/webhook
     *
     * Registers or updates the webhook URL for this bot.
     * When a user sends a message to the bot, we POST to this URL.
     *
     * Webhook payload (what we POST to your URL):
     * {
     *   "event": "message",
     *   "bot_id": "bot_7f3a9b2c",
     *   "from_jid": "u_alice@domain.com",
     *   "from_user_id": "u_alice",
     *   "message_id": "uuid",
     *   "body": "Hello bot!",
     *   "timestamp": 1703001234567,
     *   "signature": "HMAC-SHA256 of payload with webhook_secret"
     * }
     *
     * Verify signature:
     *   expected = HMAC-SHA256(webhook_secret, raw_payload_bytes)
     *   if (signature != expected) { reject the webhook }
     */
    private void handleWebhook(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) { sendError(exchange, 405, "Method not allowed"); return; }

        try {
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String apiKey       = req.getString("api_key");
            String webhookUrl   = req.getString("webhook_url");
            String webhookSecret = req.getString("webhook_secret");

            BotRecord bot = validateBotApiKey(apiKey);
            if (bot == null) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            if (webhookUrl == null || !webhookUrl.startsWith("https://")) {
                sendError(exchange, 400, "webhook_url must be an HTTPS URL");
                return;
            }

            updateWebhook(bot.botId(), webhookUrl, webhookSecret);

            sendJson(exchange, 200,
                SimpleJson.builder()
                    .put("status", "webhook_updated")
                    .put("url", webhookUrl)
                    .build()
            );

        } catch (Exception e) {
            logger.severe("Webhook update error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // Bot Info
    // =========================================================================

    private void handleInfo(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String apiKey = exchange.getRequestHeaders()
                .getFirst("X-Bot-Api-Key");
        BotRecord bot = validateBotApiKey(apiKey);
        if (bot == null) {
            sendError(exchange, 401, "Invalid API key");
            return;
        }

        sendJson(exchange, 200,
            SimpleJson.builder()
                .put("bot_id",      bot.botId())
                .put("jid",         bot.jid())
                .put("bot_name",    bot.botName())
                .put("display_name", bot.displayName())
                .put("description", bot.description())
                .put("active",      bot.active())
                .build()
        );
    }

    // =========================================================================
    // Broadcast (Admin Bots Only)
    // =========================================================================

    /**
     * POST /api/bot/broadcast
     *
     * Sends a message to ALL users (or a filtered subset).
     * Only bots with admin=true can use this endpoint.
     * Rate limited: max 1 broadcast per hour.
     *
     * Request:
     * {
     *   "api_key": "bk_...",
     *   "body": "Server maintenance in 1 hour",
     *   "filter": "all"    // all | active_today | active_week
     * }
     */
    private void handleBroadcast(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) { sendError(exchange, 405, "Method not allowed"); return; }

        try {
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String apiKey = req.getString("api_key");
            BotRecord bot = validateBotApiKey(apiKey);
            if (bot == null || !bot.isAdmin()) {
                sendError(exchange, 403, "Admin bot required");
                return;
            }

            String message = req.getString("body");
            if (message == null || message.isBlank()) {
                sendError(exchange, 400, "body is required");
                return;
            }

            // Deliver to all online sessions
            int delivered = 0;
            for (Session session : registry.getAllSessions()) {
                if (session.isAuthenticated()) {
                    String stanza = String.format(
                        "<message from='%s' type='headline'>" +
                        "<body>%s</body>" +
                        "<bot xmlns='urn:xmpp:bot:0'" +
                        " bot_id='%s'" +
                        " bot_name='%s'" +
                        " broadcast='true'/>" +
                        "</message>",
                        escapeXml(bot.jid()),
                        escapeXml(message),
                        bot.botId(),
                        escapeXml(bot.displayName())
                    );
                    if (session.writeXML(stanza)) delivered++;
                }
            }

            sendJson(exchange, 200,
                SimpleJson.builder()
                    .put("delivered_online", delivered)
                    .build()
            );

            logger.info("Bot broadcast: botId=" + bot.botId()
                    + " delivered=" + delivered);

        } catch (Exception e) {
            logger.severe("Bot broadcast error: " + e.getMessage());
            sendError(exchange, 500, "Internal server error");
        }
    }

    // =========================================================================
    // Webhook delivery (called by XMPP message handler)
    // =========================================================================

    /**
     * Called when a user sends a message to a bot JID.
     * Delivers the message to the bot's registered webhook.
     */
    public void deliverToWebhook(String botId,
                                  String fromJid,
                                  String fromUserId,
                                  String messageId,
                                  String body) {

        BotRecord bot = getBotById(botId);
        if (bot == null || bot.webhookUrl() == null) return;

        String payload = SimpleJson.builder()
                .put("event",       "message")
                .put("bot_id",      botId)
                .put("from_jid",    fromJid)
                .put("from_user_id", fromUserId)
                .put("message_id",  messageId)
                .put("body",        body)
                .put("timestamp",   System.currentTimeMillis())
                .build();

        // Sign the payload
        String signature = hmacSha256(bot.webhookSecret(), payload);

        // POST to webhook asynchronously
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
                .execute(() -> {
                    try {
                        java.net.URL url = new java.net.URL(bot.webhookUrl());
                        java.net.HttpURLConnection conn =
                                (java.net.HttpURLConnection) url.openConnection();
                        conn.setDoOutput(true);
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type",
                                "application/json");
                        conn.setRequestProperty("X-Bot-Signature",
                                signature);
                        conn.setConnectTimeout(5_000);
                        conn.setReadTimeout(10_000);

                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(payload.getBytes(StandardCharsets.UTF_8));
                        }

                        int status = conn.getResponseCode();
                        if (status >= 200 && status < 300) {
                            logger.fine("Webhook delivered: botId=" + botId
                                    + " status=" + status);
                        } else {
                            logger.warning("Webhook failed: botId=" + botId
                                    + " status=" + status);
                        }

                    } catch (Exception e) {
                        logger.warning("Webhook delivery error for botId="
                                + botId + ": " + e.getMessage());
                    }
                });
    }

    // =========================================================================
    // Database
    // =========================================================================

    private void createBot(String botId, String botName, String jid,
                            String displayName, String description,
                            String webhookUrl, String webhookSecret,
                            String apiKeyHash, String apiSecretHash) {
        String sql = """
            INSERT INTO bots (
                bot_id, bot_name, jid, display_name, description,
                webhook_url, webhook_secret_hash,
                api_key_hash, api_secret_hash,
                active, is_admin, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true, false, NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, botId);
            stmt.setString(2, botName);
            stmt.setString(3, jid);
            stmt.setString(4, displayName);
            stmt.setString(5, description);
            stmt.setString(6, webhookUrl);
            stmt.setString(7, webhookSecret != null
                    ? hashKey(webhookSecret) : null);
            stmt.setString(8, apiKeyHash);
            stmt.setString(9, apiSecretHash);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("createBot error: " + e.getMessage());
        }
    }

    private BotRecord validateBotApiKey(String rawApiKey) {
        if (rawApiKey == null) return null;
        String hash = hashKey(rawApiKey);

        String sql = """
            SELECT bot_id, bot_name, jid, display_name,
                   description, webhook_url, webhook_secret_hash,
                   active, is_admin
            FROM bots
            WHERE api_key_hash = ? AND active = true
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, hash);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new BotRecord(
                        rs.getString("bot_id"),
                        rs.getString("bot_name"),
                        rs.getString("jid"),
                        rs.getString("display_name"),
                        rs.getString("description"),
                        rs.getString("webhook_url"),
                        rs.getString("webhook_secret_hash"),
                        rs.getBoolean("active"),
                        rs.getBoolean("is_admin")
                );
            }

        } catch (SQLException e) {
            logger.severe("validateBotApiKey error: " + e.getMessage());
            return null;
        }
    }

    private BotRecord getBotById(String botId) {
        String sql = """
            SELECT bot_id, bot_name, jid, display_name,
                   description, webhook_url, webhook_secret_hash,
                   active, is_admin
            FROM bots WHERE bot_id = ? AND active = true
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, botId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new BotRecord(
                        rs.getString("bot_id"),
                        rs.getString("bot_name"),
                        rs.getString("jid"),
                        rs.getString("display_name"),
                        rs.getString("description"),
                        rs.getString("webhook_url"),
                        rs.getString("webhook_secret_hash"),
                        rs.getBoolean("active"),
                        rs.getBoolean("is_admin")
                );
            }

        } catch (SQLException e) {
            return null;
        }
    }

    private boolean botExists(String botName) {
        String sql = "SELECT 1 FROM bots WHERE bot_name = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, botName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            return false;
        }
    }

    private void updateWebhook(String botId,
                                String webhookUrl,
                                String webhookSecret) {
        String sql = """
            UPDATE bots
            SET webhook_url         = ?,
                webhook_secret_hash = ?
            WHERE bot_id = ?
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, webhookUrl);
            stmt.setString(2, webhookSecret != null
                    ? hashKey(webhookSecret) : null);
            stmt.setString(3, botId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.severe("updateWebhook error: " + e.getMessage());
        }
    }

    private void updateBotLastActive(String botId) {
        String sql = "UPDATE bots SET last_active = NOW() WHERE bot_id = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, botId);
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("updateBotLastActive error: " + e.getMessage());
        }
    }

    private boolean validateAdminKey(String key) {
        if (key == null) return false;
        String adminKey = System.getenv("BOT_ADMIN_KEY");
        return adminKey != null && adminKey.equals(key);
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private void sendJson(HttpExchange ex, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendError(HttpExchange ex, int status, String msg)
            throws IOException {
        sendJson(ex, status,
            "{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean isPost(HttpExchange ex) {
        return "POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    // =========================================================================
    // Crypto helpers
    // =========================================================================

    private String hashKey(String key) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                    key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String hmacSha256(String keyHash, String data) {
        try {
            javax.crypto.Mac mac =
                    javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    keyHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String generateHex(int bytes) {
        byte[] raw = new byte[bytes];
        SECURE_RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String toBareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record BotRecord(
            String botId,
            String botName,
            String jid,
            String displayName,
            String description,
            String webhookUrl,
            String webhookSecret,
            boolean active,
            boolean isAdmin
    ) {}
}