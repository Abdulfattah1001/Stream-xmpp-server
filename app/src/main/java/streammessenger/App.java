package streammessenger;


import java.util.logging.Logger;

import streammessenger.config.ServerConfig;

import java.util.logging.*;

/**
 * Application entry point.
 * <p>
 * Environment variables required before starting:
 *   KEYSTORE_PASSWORD  - Password for the TLS keystore
 *   KEY_PASSWORD       - Password for the TLS private key entry
 *   DB_PASSWORD        - MySQL password
 */
public final class App {

    public static void main(String[] args) {
        Logger logger = Logger.getLogger(App.class.getName());
        configureLogging();

        String configPath = args.length > 0 ? args[0] : "config.properties";

        logger.info("Loading config from: " + configPath);

        try {
            ServerConfig config = ServerConfig.load(configPath);

            Server server = new Server.Builder()
                    .setPort(config.getPort())
                    .setAddress(config.getAddress())
                    .setConfig(config)
                    .build();



            server.start(); // Blocks until shutdown signal

        } catch (IllegalStateException e) {
            System.err.println("[FATAL] Configuration error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[FATAL] Failed to start server: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);

        // Remove default handler and add a cleaner formatter
        for (Handler h : root.getHandlers()) root.removeHandler(h);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter() {
            private static final String FORMAT = "[%1$tF %1$tT] [%2$-7s] %3$s %n";
            @Override
            public synchronized String format(LogRecord lr) {
                return String.format(FORMAT,
                        new java.util.Date(lr.getMillis()),
                        lr.getLevel().getLocalizedName(),
                        lr.getMessage()
                );
            }
        });

        root.addHandler(handler);
    }
}