package streammessenger.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;

public class GroupController {
    private static final Logger logger =Logger.getLogger(GroupController.class.getName());

    private final HttpServer httpServer;
    private final DatabaseManager db;


    public GroupController(int port, DatabaseManager db) throws IOException {
        this.db = db;

        this.httpServer = HttpServer.create(new InetSocketAddress(port), 50);

        httpServer.createContext("/group/generate", this::handleLinkGenerator);
        httpServer.createContext("/group/revoke", this::handleRevoke);
        httpServer.createContext("/group/reset", this::handleLinkGenerator);
        httpServer.createContext("/group/resolve", this::handleResolve);
        httpServer.createContext("/group/join", this::handleJoin);

        httpServer.setExecutor(Executors.newFixedThreadPool(10));
    }

    public void start() {
        httpServer.start();
        logger.info("Link generator API started on port " + httpServer.getAddress().getPort());
    }

    public void stop() { httpServer.stop(5); }

    private void handleLinkGenerator(HttpExchange exchange) throws IOException {
        if(!isPost(exchange)){
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try{
            String body = readBody(exchange);
            SimpleJson req = SimpleJson.parse(body);

            String groupId = req.getString("groupId");

            String creatorId = req.getString("creatorId");

            // Only admin can create a link for the group

            boolean isAdmin = db.isGroupAdmin(groupId, creatorId);

            if(!isAdmin){
                logger.info("Only admin can create a link for a group");
                sendError(exchange, 403, "Only group admins can manage invite links");
                return;
            }

            Optional<ChatGroup> group = db.getGroup(groupId);

            if(group.isEmpty()){
                logger.info("The group does not exist");
                sendError(exchange, 403, "The group does not exist");
                return;
            }


            // Generate the link token
            String token;
            int attempts = 0;
            do {
                token = InviteTokenGenerator.generate();
                if(++attempts > 5) {
                    logger.info("Maximum attempts reached");
                    sendError(exchange, 403, "Maximum attempts reached, please try again later");
                    return;
                }
            } while(db.isTokenExist(token));

            // Updates the invite token
            db.updateGroupLink(groupId, creatorId, token, true);

            sendJson(exchange, 200, "{\"inviteLink\":\"" + "BASE_URL" + token + "\"}");

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- Resolve the link into the group metadata for the user to preview
    private void handleResolve(HttpExchange  exchange) throws IOException {
        //TODO:
        // Resolve the group metadata (name, description, date created, icon url, members count, two members)
        // Or Error if the group does not exist
    }
    private void handleRevoke(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) { sendError(exchange, 405, "Method not allowed"); return; }

        try {

            String groupId = SimpleJson.parse(readBody(exchange)).getString("groupId");
            String userId = SimpleJson.parse(readBody(exchange)).getString("userId");
            if (groupId == null || db.getGroup(groupId).isEmpty()) {
                sendError(exchange, 404, "Group not found"); return;
            }
            if (!db.isGroupAdmin(groupId, userId)) {
                sendError(exchange, 403, "Only group admins can revoke links"); return;
            }

            db.disableInviteToken(groupId);
            sendJson(exchange, 200, "{\"status\":\"revoked\"}");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "revoke failed", e);
            sendError(exchange, 500, "Internal server error");
        }
    }

    // ---- Join via link -------------------------------------------------
    private void handleJoin(HttpExchange exchange) throws IOException {
        if (!isPost(exchange)) { sendError(exchange, 405, "Method not allowed"); return; }

        try {

            String userId = "";
            String path = exchange.getRequestURI().getPath();
            String token = path.substring("/join/".length());
            if (token.isBlank() || token.contains("/")) {
                sendError(exchange, 400, "Invalid invite link");
                return;
            }

            Optional<ChatGroup> groupOpt = db.getGroupByToken(token);
            if (groupOpt.isEmpty()) {
                sendError(exchange, 404, "Invite link is invalid or revoked");
                return;
            }
            ChatGroup group = groupOpt.get();

            if (!group.isInviteEnabled()) {
                sendError(exchange, 410, "This invite link has been disabled");
                return;
            }

            if (db.isMember(group.getGroupId(), userId)) {
                sendJson(exchange, 200,
                        "{\"status\":\"already_member\",\"groupId\":\"" + group.getGroupId() + "\"}");
                return;
            }

            db.addMemberIfAbsent(group.getGroupId(), userId);

            sendJson(exchange, 200,
                    "{\"status\":\"joined\",\"groupId\":\"" + group.getGroupId() +
                            "\",\"name\":\"" + group.getName() + "\"}");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "join failed", e);
            sendError(exchange, 500, "Internal server error");
        }
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
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
    private boolean isPost(HttpExchange ex) {
        return "POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    public static class ChatGroup {
        private String groupId;
        private String name;
        private String description;
        private String memberCount;

        private boolean isInviteEnabled;

        public String getGroupId() {
            return groupId;
        }

        public String getName() {
            return name;
        }

        public String getMemberCount() {
            return memberCount;
        }

        public String getDescription() {
            return description;
        }

        public boolean isInviteEnabled() {
            return isInviteEnabled;
        }
    }
}