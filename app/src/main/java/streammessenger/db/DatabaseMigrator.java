package streammessenger.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import streammessenger.config.ServerConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class DatabaseMigrator {

    private static final Logger logger = Logger.getLogger(DatabaseMigrator.class.getName());

    private DatabaseMigrator() {}

    /**
     * Runs pending database migrations.
     * Must be called ONCE at server startup BEFORE initializing the connection pool or application handlers.
     */
    public static void migrate(ServerConfig config) {
        String dbPassword = System.getenv("DB_PASSWORD");
        if (dbPassword == null || dbPassword.isBlank()) {
            throw new IllegalStateException("DB_PASSWORD environment variable is not set");
        }

        logger.info("Initializing Flyway database migration against: " + config.getDbUrl());

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(config.getDbUrl(), config.getDbUser(), dbPassword)
                    .locations("classpath:db/migration")
                    // Baseline existing database if Flyway schema history table doesn't exist yet
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();

            int migrationsApplied = flyway.migrate().migrationsExecuted;
            logger.info("Flyway migration completed successfully. Applied " 
                    + migrationsApplied + " new migration(s).");

        } catch (FlywayException e) {
            logger.log(Level.SEVERE, "Database migration failed!", e);
            throw new RuntimeException("Database migration failed. Server startup aborted.", e);
        }
    }
}