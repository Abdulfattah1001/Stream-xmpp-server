package streammessenger.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;

import java.util.LinkedHashMap;
import java.util.Map;

public class HealthCheckHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckHandler.class);
    private final DataSource dataSource;

    public HealthCheckHandler(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, Object> responseMap = new LinkedHashMap<>();
        boolean dbHealthy = checkDatabaseHealth();
        boolean diskHealthy = checkDiskSpaceHealth();

        boolean isOverallHealthy = dbHealthy && diskHealthy;

        responseMap.put("status", isOverallHealthy ? "UP" : "DOWN");
        
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("database", dbHealthy ? "UP" : "DOWN");
        details.put("diskSpace", diskHealthy ? "UP" : "DOWN");
        details.put("jvmMemory", getMemoryMetrics());
        responseMap.put("components", details);


        //byte[] jsonBytes = SimpleJson.toJson(responseMap).getBytes();
        byte[] jsonBytes = new byte[]{};
        
        int statusCode = isOverallHealthy ? 200 : 503;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonBytes);
        }
    }

    private boolean checkDatabaseHealth() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2); // 2 second timeout validation
        } catch (Exception e) {
            logger.error("Health check failed: Database connection error", e);
            return false;
        }
    }

    private boolean checkDiskSpaceHealth() {
        File root = new File(".");
        long freeSpaceBytes = root.getFreeSpace();
        long thresholdBytes = 500 * 1024 * 1024; // 500 MB minimum required free disk
        return freeSpaceBytes > thresholdBytes;
    }

    private Map<String, Object> getMemoryMetrics() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMemoryMb", runtime.maxMemory() / (1024 * 1024));
        memory.put("totalMemoryMb", runtime.totalMemory() / (1024 * 1024));
        memory.put("freeMemoryMb", runtime.freeMemory() / (1024 * 1024));
        return memory;
    }
}