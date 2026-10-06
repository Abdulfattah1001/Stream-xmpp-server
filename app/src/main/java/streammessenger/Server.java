package streammessenger;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import streammessenger.api.AuthController;
import streammessenger.api.BotApiHandler;
import streammessenger.auth.AuthManager;
import streammessenger.auth.FirebaseTokenVerifier;
import streammessenger.auth.SessionTokenService;
import streammessenger.call.CallSignalingHandler;
import streammessenger.config.ServerConfig;
import streammessenger.connection.ConnectionHandler;
import streammessenger.connection.TLSUpgrader;
import streammessenger.crypto.PhoneEncryption;
import streammessenger.db.CleanupTask;
import streammessenger.db.ConnectionPool;
import streammessenger.db.DatabaseManager;
import streammessenger.features.CollaborativeNoteHandler;
import streammessenger.features.TranslationService;
import streammessenger.group.GroupManager;
import streammessenger.group.handler.GroupStanzaHandler;
import streammessenger.group.repository.GroupRepository;
import streammessenger.group.service.GroupEventNotifier;
import streammessenger.group.service.GroupMessageRouter;
import streammessenger.group.service.GroupService;
import streammessenger.group.service.GroupSyncService;
import streammessenger.metrics.ServerMetrics;
import streammessenger.mutlidevice.CarbonManager;
import streammessenger.mutlidevice.DeviceManager;
import streammessenger.mutlidevice.MultiDeviceMessageHandler;
import streammessenger.profile.ProfileStore;
import streammessenger.push.PushNotificationService;
import streammessenger.roster.ChangeSequencer;
import streammessenger.roster.PrivacyEngine;
import streammessenger.roster.RosterManager;
import streammessenger.security.RateLimiter;
import streammessenger.session.Session;
import streammessenger.session.SessionReaper;
import streammessenger.session.SessionRegistry;
import streammessenger.stanza.CarbonHandler;
import streammessenger.stanza.ReactionHandler;
import streammessenger.stanza.VerifiedAccountHandler;
import streammessenger.stream.XMPPStreamProcessor;
import streammessenger.sync.CounterRowSequencer;
import streammessenger.sync.SyncChangeLog;
import streammessenger.sync.SyncNode;
import streammessenger.sync.SyncWorker;
import streammessenger.vhost.DomainConfig;
import streammessenger.vhost.VirtualHostManager;

/**
 * XMPP Server - root component.
 * <p>
 * Owns and wires all top-level components.
 * Manages connection accept loop and server lifecycle.
 * <p>
 * Component graph:
 * <p>
 *   Server
 *   ├── ServerConfig          (immutable config + SSLContext)
 *   ├── ConnectionPool        (DB connections)
 *   ├── DatabaseManager       (all SQL operations)
 *   ├── SessionRegistry       (all active sessions)
 *   ├── VirtualHostManager    (domain routing)
 *   ├── RosterManager         (contact list operations)
 *   ├── AuthManager           (SASL + rate limiting)
 *   ├── TLSUpgrader           (socket → SSLSocket)
 *   ├── XMPPStreamProcessor   (XML dispatch + SM)
 *   ├── SessionReaper         (idle session cleanup)
 *   └── ServerMetrics         (counters + gauges)
 */
@SuppressWarnings("ALL")
public class Server {

    private static final Logger logger =
            Logger.getLogger(Server.class.getName());

    private static final SecureRandom secureRandom = new SecureRandom();

    // -------------------------------------------------------------------------
    // Backward-compatible public fields
    // -------------------------------------------------------------------------

    /** Direct access to session map. Prefer SessionRegistry methods. */
    public static final ConcurrentHashMap<String, Session> connections =
            SessionRegistry.getInstance().getRawMap();

    /** Shared executor for async notification delivery. */
    public static final ExecutorService NOTIFICATION_EXECUTOR =
            new ThreadPoolExecutor(
                    4, 100,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(5000),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

    // -------------------------------------------------------------------------
    // Core components
    // -------------------------------------------------------------------------

    private final int PORT;
    private final String address;
    private final ServerConfig config;

    private final ConnectionPool connectionPool;
    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final ServerMetrics metrics;
    private final VirtualHostManager vhostManager;
    private final RosterManager rosterManager;
    private final CleanupTask cleanupTask;
    private final AuthManager authManager;
    private final TLSUpgrader tlsUpgrader;
    private final XMPPStreamProcessor streamProcessor;
    private final SessionReaper sessionReaper;

    private final AuthController authController;
    private final SessionTokenService sessionTokenService;
    private final FirebaseTokenVerifier firebaseTokenVerifier;
    private final PushNotificationService pushService;
    private final GroupManager groupManager;
    private final CallSignalingHandler callHandler;
    private final BotApiHandler botApiHandler;
    private final VerifiedAccountHandler verifiedHandler;
    private final TranslationService translationService;
    private final CollaborativeNoteHandler noteHandler;
    //private final ScheduledMessageHandler scheduledMsgHandler;
    private final ReactionHandler reactionHandler;
    private final PhoneEncryption phoneEncryption;
    private final RateLimiter rateLimiter;
    private final CarbonManager carbonManager;
    private final DeviceManager deviceManager;
    private final MultiDeviceMessageHandler multiDeviceHandler;

    //MUC
    private final GroupRepository groupRepository;
    private final GroupStanzaHandler groupStanzaHandler;
    private final PrivacyEngine privacyEngine;
    private final SyncNode syncNode;


    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    // Periodic cleanup tasks
    private final ScheduledExecutorService maintenanceExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "server-maintenance");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService globalAckScheduler = Executors.newScheduledThreadPool(
            4,
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "xmpp-sm-ack-worker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true); // Allows JVM to exit cleanly
                    return t;
                }
            }
    );

    /*private final SyncWorker syncWorker;
    private final ProfileUpdateServices updateServices;*/

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile Server instance = null;

    // =========================================================================
    // Constructor - private, use Builder
    // =========================================================================

    private Server(Builder builder) throws IOException {
        this.PORT    = builder.port;
        this.address = builder.address;
        this.config  = builder.config;


        // Wire dependency graph bottom-up
        this.registry = SessionRegistry.getInstance();
        this.metrics  = ServerMetrics.getInstance();

        this.connectionPool = new ConnectionPool(config);
        this.db             = new DatabaseManager(connectionPool, "localhost");
        this.sessionTokenService = new SessionTokenService(this.db);

        CarbonManager.initialize(connectionPool, registry);
        this.carbonManager = CarbonManager.getInstance();

        // Initialize device manager
        this.deviceManager = new DeviceManager(connectionPool, registry);

        // Initialize multi-device message routing
        this.multiDeviceHandler = new MultiDeviceMessageHandler(registry, db);

        this.firebaseTokenVerifier =  new FirebaseTokenVerifier(config.getFcmProjectId());

        this.groupRepository      = new GroupRepository(connectionPool,
                "conference." + config.getDomainName());

        this.rosterManager = new RosterManager(db, registry);

        this.privacyEngine = new PrivacyEngine(db, rosterManager);

        GroupEventNotifier notifier = new GroupEventNotifier(groupRepository, registry);
        this.groupStanzaHandler = new GroupStanzaHandler(groupRepository,
                new GroupService(groupRepository, notifier),
                new GroupMessageRouter(groupRepository, registry, db), new GroupSyncService(groupRepository, notifier),
                registry,
                "conference."+config.getDomainName());

        try{
            this.authController = new AuthController(3004, this.db, firebaseTokenVerifier, sessionTokenService);
        } catch (IOException e) {
            throw new RuntimeException(e); //Intentional pass through
        }

        this.vhostManager  = new VirtualHostManager(registry);
        this.authManager   = new AuthManager(db, registry, metrics, sessionTokenService, this.firebaseTokenVerifier);
        this.tlsUpgrader   = new TLSUpgrader(config);

        this.cleanupTask = new CleanupTask(connectionPool);

        // Push notifications
        this.pushService = new PushNotificationService(
                config.getFcmProjectId(),
                config.getApnsBundleId(),
                config.isDev(),
                db, config
        );

        // Voice/Video call signaling
        this.callHandler = new CallSignalingHandler(
                connectionPool, registry, pushService, config);

        this.syncNode = new SyncNode(connectionPool,  config, metrics, registry, new SyncChangeLog(connectionPool,  new CounterRowSequencer()), new ProfileStore());

        this.streamProcessor = new XMPPStreamProcessor(
                db, registry, authManager, rosterManager, metrics, connectionPool,
                new CarbonHandler(carbonManager, deviceManager),
                multiDeviceHandler, callHandler, config, this.syncNode);

        this.sessionReaper = new SessionReaper(
                registry, metrics,
                config.getSessionTimeoutMs(),
                config.getReaperIntervalSec()
        );

        this.streamProcessor.registerGroupHandler(groupStanzaHandler);

        // Register primary domain from config
        vhostManager.registerDomain(extractPrimaryDomain(),
                new DomainConfig.Builder(extractPrimaryDomain())
                        .registrationOpen(false)
                        .federationEnabled(false)
                        .description("Primary XMPP domain")
                        .build()
        );

        this.phoneEncryption = PhoneEncryption.initialize();

        // Rate limiter singleton
        this.rateLimiter = RateLimiter.getInstance();

        // Group messaging
        this.groupManager = new GroupManager(
                connectionPool, db, registry, pushService);


        // Bot API
        this.botApiHandler = new BotApiHandler(
                config.getBotApiPort(),
                connectionPool, db, registry, config);

        // Verified accounts
        this.verifiedHandler = new VerifiedAccountHandler(
                connectionPool, registry);

        // Translation (self-hosted LibreTranslate)
        this.translationService = new TranslationService();

        // Collaborative notes
        this.noteHandler = new CollaborativeNoteHandler(
                connectionPool, registry);

        // Scheduled messages
        /*this.scheduledMsgHandler = new ScheduledMessageHandler(
                connectionPool, db, registry,
                );*/

        // Reactions
        this.reactionHandler = new ReactionHandler(connectionPool, registry);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public static Server getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                    "Server not initialized. Call Server.Builder.build() first.");
        }
        return instance;
    }

    /**
     * Starts the server. Blocks until shutdown signal received.
     * <p>
     * Startup order:
     *  1. Register JVM shutdown hook
     *  2. Start session reaper daemon
     *  3. Start maintenance tasks (auth cleanup, metrics logging)
     *  4. Bind server socket
     *  5. Accept connection loop
     *  6. On shutdown: drain pool, close all sessions, log final metrics
     */
    public void start() {
        registerShutdownHook();
        sessionReaper.start();
        authController.start();
        botApiHandler.start();
        cleanupTask.start();
        startMaintenanceTasks();

        ThreadPoolExecutor jobPool = buildJobPool();
        jobPool.prestartCoreThread();
        try {
            //syncWorker.start();
            syncNode.start();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            running = true;

            logger.info(String.format(
                    "XMPP Server started [address=%s, port=%d, env=%s, " +
                            "corePool=%d, maxPool=%d, domains=%s]",
                    address, PORT,
                    config.getEnvironment(),
                    config.getCorePoolSize(),
                    config.getMaxPoolSize(),
                    vhostManager.getLocalDomains()
            ));

            acceptLoop(jobPool);

        } catch (IOException e) {
            logger.severe("Fatal: cannot bind to port " + PORT
                    + ": " + e.getMessage());
        } finally {
            shutdown(jobPool);
        }
    }

    /** Backward-compatible static method. */
    public static void startSessionReaper() {
        getInstance().sessionReaper.start();
    }

    public DatabaseManager getDB() { return db; }

    public VirtualHostManager getVhostManager() { return vhostManager; }

    public ServerMetrics getMetrics() { return metrics; }

    // =========================================================================
    // Private - accept loop
    // =========================================================================

    private void acceptLoop(ThreadPoolExecutor jobPool) {

        while (running) {
            try {
                Socket connection = serverSocket.accept();
                configureSocket(connection);

                // Enforce connection limit - reject early if saturated
                if (jobPool.getQueue().remainingCapacity() == 0) {
                    metrics.connectionRejected();
                    logger.warning("Job pool saturated - rejecting: "
                            + connection.getInetAddress().getHostAddress());
                    sendResourceConstraint(connection);
                    connection.close();
                    continue;
                }

                String sessionId = hexSessionId();
                jobPool.execute(new ConnectionHandler(
                        connection, sessionId,
                        tlsUpgrader, streamProcessor,
                        registry, metrics,
                        globalAckScheduler
                ));

            } catch (IOException e) {
                if (running) {
                    logger.warning("Accept loop error: " + e.getMessage());
                }
                // If !running: shutdown requested, exit cleanly
            }
        }
    }

    // =========================================================================
    // Private - lifecycle helpers
    // =========================================================================

    private void startMaintenanceTasks() {
        // Clean expired auth failure records every 10 minutes
        maintenanceExecutor.scheduleAtFixedRate(
                authManager::cleanExpiredFailureRecords,
                10, 10, TimeUnit.MINUTES
        );

        // Log metrics summary every 5 minutes
        maintenanceExecutor.scheduleAtFixedRate(
                metrics::logSummary,
                5, 5, TimeUnit.MINUTES
        );
    }

    private void configureSocket(Socket socket) throws IOException {
        socket.setKeepAlive(true);
        socket.setSoTimeout(90_000);   // 90s read timeout
        socket.setTcpNoDelay(true);    // No Nagle - XMPP is latency sensitive
        socket.setReceiveBufferSize(8192);
        socket.setSendBufferSize(8192);
    }

    private ThreadPoolExecutor buildJobPool() {
        return new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Sends an XMPP stream-level error before refusing an overloaded connection.
     * Gives the client a proper protocol-level rejection instead of a TCP reset.
     */
    private void sendResourceConstraint(Socket socket) {
        try {
            OutputStreamWriter w = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8);
            w.write(
                    "<?xml version='1.0'?>" +
                            "<stream:stream " +
                            "xmlns:stream='http://etherx.jabber.org/streams'>" +
                            "<stream:error>" +
                            "<resource-constraint " +
                            "xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
                            "</stream:error>" +
                            "</stream:stream>"
            );
            w.flush();
        } catch (IOException ignored) {}
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received.");
            running = false;
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close(); // Unblocks accept()
                }
            } catch (IOException e) {
                logger.warning("Error closing server socket: " + e.getMessage());
            }
        }, "server-shutdown-hook"));
    }

    /**
     * Orderly shutdown:
     *  1. Stop accepting (server socket already closed by hook)
     *  2. Stop session reaper
     *  3. Stop maintenance tasks
     *  4. Wait for active connection handlers to finish (max 30s)
     *  5. Force-close remaining sessions
     *  6. Shut down stream processor (saves SM states)
     *  7. Close DB connection pool
     *  8. Shut down notification executor
     *  9. Log final metrics
     */
    private void shutdown(ExecutorService jobPool) {
        logger.info("Shutting down...");

        authController.stop();

        sessionReaper.stop();

        maintenanceExecutor.shutdown();

        jobPool.shutdown();
        try {
            if (!jobPool.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("Job pool did not terminate cleanly - forcing.");
                jobPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            jobPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close all remaining sessions
        for (Session session : registry.getAllSessions()) {
            session.closeQuietly();
        }

        // Notify stream processor (saves SM states for resumption)
        for (Session session : registry.getAllSessions()) {
            streamProcessor.onSessionDisconnect(session);
        }

        streamProcessor.shutdown();
        cleanupTask.stop();
        callHandler.shutdown();
        //scheduledMsgHandler.shutdown();
        translationService.shutdown();
        pushService.shutdown();
        connectionPool.shutdown();

        NOTIFICATION_EXECUTOR.shutdown();
        try {
            if (!NOTIFICATION_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                NOTIFICATION_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            NOTIFICATION_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }

        metrics.logSummary();
        logger.info("Shutdown complete.");
    }

    // =========================================================================
    // ID generation
    // =========================================================================

    private String hexSessionId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Deprecated
    private String uniqueIdGenerator() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // =========================================================================
    // Config helpers
    // =========================================================================

    /**
     * Extracts the primary domain from the server address config.
     * Falls back to "localhost" if address is an IP.
     */
    private String extractPrimaryDomain() {
        if (address == null || address.isBlank()
                || address.equals("0.0.0.0") || address.equals("127.0.0.1")) {
            return "localhost";
        }
        return address;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static class Builder {
        private int port = 5222;
        private String address = "0.0.0.0";
        private ServerConfig config;

        public Builder setPort(int port) {
            this.port = port;
            return this;
        }

        public Builder setAddress(String address) {
            this.address = address;
            return this;
        }

        public Builder setConfig(ServerConfig config) {
            this.config = config;
            return this;
        }

        public int getPort()         { return port; }
        public String getAddress()   { return address; }
        public ServerConfig getConfig() { return config; }

        public Server build() throws IOException {
            if (instance == null) {
                synchronized (Server.class) {
                    if (instance == null) {
                        if (config == null) {
                            throw new IllegalStateException(
                                    "ServerConfig is required. " +
                                            "Call setConfig(ServerConfig.load(\"config.properties\"))."
                            );
                        }
                        instance = new Server(this);
                    }
                }
            }
            return instance;
        }
    }
}