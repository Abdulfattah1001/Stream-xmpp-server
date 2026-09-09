package streammessenger.config;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Immutable server configuration.
 * - SSL context is created ONCE and reused for all connections
 * - Secrets are read from environment variables (never from source code)
 * - All values have sensible defaults
 */
public final class ServerConfig {

    private static final Logger logger = Logger.getLogger(ServerConfig.class.getName());

    // Only strong protocols allowed
    public static final String[] TLS_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

    private final int port;
    private final String address;
    private final String domainName;
    private final String environment;
    private final String fcmProjectId;
    private final String fcmServiceAccountJson;
    private final String apnsBundleId;
    private final int botApiPort;
    private final SSLContext sslContext;
    private final int corePoolSize;
    private final int maxPoolSize;
    private final int notificationCorePool;
    private final int notificationMaxPool;
    private final long sessionTimeoutMs;
    private final long reaperIntervalSec;
    private final String dbUrl;
    private final String dbUser;
    private final int dbPoolMin;
    private final int dbPoolMax;
    private final long dbPoolTimeout;
    private final String cloudinaryCloudname;
    private final String cloudinaryApiKey;
    private final String cloudinaryApiSecret;

    private ServerConfig(Builder b) {
        this.port = b.port;
        this.address = b.address;
        this.domainName = b.domainName;
        this.environment = b.environment;
        this.sslContext = b.sslContext;
        this.corePoolSize = b.corePoolSize;
        this.maxPoolSize = b.maxPoolSize;
        this.notificationCorePool = b.notificationCorePool;
        this.notificationMaxPool = b.notificationMaxPool;
        this.sessionTimeoutMs = b.sessionTimeoutMs;
        this.reaperIntervalSec = b.reaperIntervalSec;
        this.dbUrl = b.dbUrl;
        this.dbUser = b.dbUser;
        this.dbPoolMin = b.dbPoolMin;
        this.dbPoolMax = b.dbPoolMax;
        this.dbPoolTimeout = b.dbPoolTimeout;
        this.fcmProjectId = b.fcmProjectId;
        this.fcmServiceAccountJson = b.fcmServiceAccountJson;
        this.apnsBundleId = b.apnsBundleId;
        this.botApiPort = b.botApiPort;
        this.cloudinaryCloudname = b.cloudinaryCloudName;
        this.cloudinaryApiKey = b.cloudinaryApiKey;
        this.cloudinaryApiSecret = b.cloudinaryApiSecret;
    }

    /**
     * Primary factory method. Loads everything from a properties file.
     * Passwords are sourced from environment variables only.
     */
    public static ServerConfig load(String propertiesPath)
            throws IOException, GeneralSecurityException {

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
        }

        String env = props.getProperty("ENV", "PROD");
        boolean isDev = "DEV".equalsIgnoreCase(env);

        String certPath = isDev
                ? requireProperty(props, "devTlsCertPath")
                : requireProperty(props, "prodTlsCertPath");

        // Passwords MUST come from env variables - never properties files
        //char[] ksPassword = requireSecret("KEYSTORE_PASSWORD");
        //char[] keyPassword = requireSecret("KEY_PASSWORD");

        SSLContext sslContext;

        if(isDev){
            // Passwords MUST come from env variables - never properties files
            char[] ksPassword = requireSecret("KEYSTORE_PASSWORD");
            char[] keyPassword = requireSecret("KEY_PASSWORD");

            try{
                sslContext = buildSSLContext(certPath, ksPassword, keyPassword);
            }finally {
                // Zero out secrets immediately after use for security purposes WARNING
                Arrays.fill(ksPassword, '\0');
                Arrays.fill(keyPassword, '\0');
            }
        }else {
            logger.info("Loading the default ssl context, and lets Nginx manages the TLS");
            sslContext = SSLContext.getDefault();
        }

        logger.info("SSLContext initialized [env=" + env + ", cert=" + certPath + "]");

        return new Builder()
                .port(intProp(props, "port", 5222))
                .botApiPort(intProp(props, "botApiPort", 5224))
                .fcmProjectId(props.getProperty("fcmProjectId", "stream-6fa32"))
                .fcmServiceAccountJson(props.getProperty("fcmServiceAccountJson", ""))
                .apnsBundleId(props.getProperty("apnsBundleId", ""))
                .address(props.getProperty("address", "0.0.0.0"))
                .domainName(props.getProperty("domainName", "chat.omnyrex.com"))
                .environment(env)
                .sslContext(sslContext)
                .corePoolSize(intProp(props, "corePoolSize", 10))
                .maxPoolSize(intProp(props, "maxPoolSize", 200))
                .notificationCorePool(intProp(props, "notificationCorePool", 4))
                .notificationMaxPool(intProp(props, "notificationMaxPool", 100))
                .sessionTimeoutMs(longProp(props, "sessionTimeoutMs", 120_000L))
                .reaperIntervalSec(longProp(props, "reaperIntervalSec", 30L))
                .dbUrl(requireProperty(props, "db.url"))
                .dbUser(requireProperty(props, "db.user"))
                .dbPoolMin(intProp(props, "db.pool.min", 5))
                .dbPoolMax(intProp(props, "db.pool.max", 20))
                .dbPoolTimeout(longProp(props, "db.pool.timeout", 30_000L))
                .cloudinaryCloudName(props.getProperty("cloudinary_cloudname"))
                .cloudinaryApiKey(props.getProperty("cloudinary_api_key"))
                .cloudinaryApiSecret(props.getProperty("cloudinary_api_secret"))
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static SSLContext buildSSLContext(String certPath, char[] ksPass, char[] keyPass)
            throws GeneralSecurityException, IOException {

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(certPath)) {
            ks.load(fis, ksPass);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPass);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /**
     * Reads a required secret from environment variables.
     * Throws a clear error if missing rather than failing silently.
     */
    private static char[] requireSecret(String envKey) {
        String val = System.getenv(envKey);
        if (val == null || val.isEmpty()) {
            throw new IllegalStateException(
                "Required environment variable not set: " + envKey +
                ". Set it before starting the server."
            );
        }
        return val.toCharArray();
    }

    private static String requireProperty(Properties props, String key) {
        String val = props.getProperty(key);
        if (val == null || val.isEmpty()) {
            throw new IllegalStateException("Required property missing: " + key);
        }
        return val;
    }

    private static int intProp(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private static long longProp(Properties p, String key, long def) {
        try { return Long.parseLong(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public int getPort() { return port; }

    public int getBotApiPort() {return botApiPort;}

    public String getAddress() { return address; }

    public String getDomainName() { return domainName; }

    public String getEnvironment() { return environment; }

    public String getFcmProjectId() { return fcmProjectId; }

    public String getFcmServiceAccountJson() { return fcmServiceAccountJson; }

    public String getApnsBundleId() { return apnsBundleId; }

    public boolean isDev() { return "DEV".equalsIgnoreCase(environment); }
    public SSLContext getSslContext() { return sslContext; }
    public int getCorePoolSize() { return corePoolSize; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public int getNotificationCorePool() { return notificationCorePool; }
    public int getNotificationMaxPool() { return notificationMaxPool; }
    public long getSessionTimeoutMs() { return sessionTimeoutMs; }
    public long getReaperIntervalSec() { return reaperIntervalSec; }
    public String getDbUrl() { return dbUrl; }
    public String getDbUser() { return dbUser; }
    public int getDbPoolMin() { return dbPoolMin; }
    public int getDbPoolMax() { return dbPoolMax; }
    public long getDbPoolTimeout() { return dbPoolTimeout; }
    public String getCloudinaryCloudName() { return cloudinaryCloudname; }
    public String getCloudinaryApiKey()     { return cloudinaryApiKey; }
    public String getCloudinaryApiSecret()  { return cloudinaryApiSecret; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {
        private int port = 5222;
        private int botApiPort = 5223;
        private String address = "0.0.0.0";
        private String domainName;
        private String environment = "DEV";
        private String fcmProjectId;
        private String fcmServiceAccountJson;
        private String apnsBundleId;
        private SSLContext sslContext;
        private int corePoolSize = 10;
        private int maxPoolSize = 200;
        private int notificationCorePool = 4;
        private int notificationMaxPool = 100;
        private long sessionTimeoutMs = 120_000L;
        private long reaperIntervalSec = 30L;
        private String dbUrl;
        private String dbUser;
        private int dbPoolMin = 5;
        private int dbPoolMax = 20;
        private long dbPoolTimeout = 30_000L;
        private String cloudinaryCloudName;
        private String cloudinaryApiKey;
        private String cloudinaryApiSecret;

        public Builder port(int v) { this.port = v; return this; }
        public Builder botApiPort(int v) { this.botApiPort = v; return this; }
        public Builder address(String v) { this.address = v; return this; }

        public Builder domainName(String v) { this.domainName = v; return this; }

        public Builder fcmProjectId(String v) { this.fcmProjectId = v; return this; }
        public Builder fcmServiceAccountJson(String v) { this.fcmServiceAccountJson = v; return  this; }
        public Builder apnsBundleId(String v) { this.apnsBundleId = v; return this; }
        public Builder environment(String v) { this.environment = v; return this; }
        public Builder sslContext(SSLContext v) { this.sslContext = v; return this; }
        public Builder corePoolSize(int v) { this.corePoolSize = v; return this; }
        public Builder maxPoolSize(int v) { this.maxPoolSize = v; return this; }
        public Builder notificationCorePool(int v) { this.notificationCorePool = v; return this; }
        public Builder notificationMaxPool(int v) { this.notificationMaxPool = v; return this; }
        public Builder sessionTimeoutMs(long v) { this.sessionTimeoutMs = v; return this; }
        public Builder reaperIntervalSec(long v) { this.reaperIntervalSec = v; return this; }
        public Builder dbUrl(String v) { this.dbUrl = v; return this; }
        public Builder dbUser(String v) { this.dbUser = v; return this; }
        public Builder dbPoolMin(int v) { this.dbPoolMin = v; return this; }
        public Builder dbPoolMax(int v) { this.dbPoolMax = v; return this; }
        public Builder dbPoolTimeout(long v) { this.dbPoolTimeout = v; return this; }
        public Builder cloudinaryCloudName(String v) { this.cloudinaryCloudName = v; return this; }
        public Builder cloudinaryApiKey(String v)   {  this.cloudinaryApiKey = v; return  this; }

        public Builder cloudinaryApiSecret(String v) { this.cloudinaryApiSecret = v; return this; }

        public ServerConfig build() {
            if (sslContext == null) throw new IllegalStateException("SSLContext is required");
            if (dbUrl == null) throw new IllegalStateException("Database URL is required");
            return new ServerConfig(this);
        }
    }
}