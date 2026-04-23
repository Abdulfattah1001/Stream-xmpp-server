package streammessenger.features;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Real-time message translation using LibreTranslate (self-hosted).
 * <p>
 * WHY LIBRETRANSLATE NOT GOOGLE TRANSLATE:
 * ─────────────────────────────────────────
 * - Self-hosted: your data never leaves your server
 * - Free and open source
 * - No API key costs
 * - Respects E2E encryption model
 *   (translation happens ON the client for E2E messages,
 *    server translation is for non-E2E content like blog posts)
 * <p>
 * HOW TRANSLATION WORKS WITH E2E:
 * ─────────────────────────────────
 * For E2E messages:
 *   1. Client decrypts the message
 *   2. Client sends PLAINTEXT to this service (only if user opts in)
 *   3. Service translates and returns
 *   4. Client shows translated version
 *   This means the CLIENT calls the translation API, not the server.
 * <p>
 * For non-E2E content (blog posts, status text):
 *   Server can translate directly since it has the plaintext.
 * <p>
 * SETUP (self-hosted LibreTranslate):
 *   docker run -p 5000:5000 libretranslate/libretranslate
 *   export LIBRETRANSLATE_URL=http://localhost:5000
 *   export LIBRETRANSLATE_API_KEY=optional_key
 * <p>
 * INSTALL MODELS (languages you want to support):
 *   libretranslate --install-files
 */
public final class TranslationService {

    private static final Logger logger =
            Logger.getLogger(TranslationService.class.getName());

    private static final String DEFAULT_URL = "http://localhost:5000";

    private final String apiUrl;
    private final String apiKey; // Optional, empty if not required

    // Cache: (text|sourceLang|targetLang) → translation
    // Prevents re-translating the same text
    private final ConcurrentHashMap<String, String> cache =
            new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10_000;

    // Thread pool for async translation
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    public TranslationService() {
        this.apiUrl = System.getenv().getOrDefault(
                "LIBRETRANSLATE_URL", DEFAULT_URL);
        this.apiKey = System.getenv().getOrDefault(
                "LIBRETRANSLATE_API_KEY", "");

        logger.info("Translation service initialized: " + apiUrl);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Translates text synchronously.
     *
     * @param text       The text to translate
     * @param sourceLang Source language code ("auto" for auto-detect)
     *                   or "en", "fr", "es", "ar", "yo", "ha", "ig" etc.
     * @param targetLang Target language code: "en", "fr", "es", etc.
     * @return Translated text, or original if translation fails
     */
    public String translate(String text,
                             String sourceLang,
                             String targetLang) {

        if (text == null || text.isBlank()) return text;
        if (targetLang == null || targetLang.isBlank()) return text;
        if (targetLang.equals(sourceLang)) return text;

        // Check cache
        String cacheKey = text.hashCode() + "|" + sourceLang + "|" + targetLang;
        String cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String translated = callLibreTranslate(text, sourceLang, targetLang);

            // Cache result
            if (cache.size() < MAX_CACHE_SIZE) {
                cache.put(cacheKey, translated);
            }

            return translated;

        } catch (Exception e) {
            logger.warning("Translation failed: " + e.getMessage());
            return text; // Return original on failure
        }
    }

    /**
     * Translates text asynchronously.
     * Returns a CompletableFuture that completes with the translation.
     */
    public CompletableFuture<String> translateAsync(String text,
                                                     String sourceLang,
                                                     String targetLang) {
        return CompletableFuture.supplyAsync(
                () -> translate(text, sourceLang, targetLang),
                executor
        );
    }

    /**
     * Detects the language of a text.
     *
     * @param text Sample text (at least 20 chars for accuracy)
     * @return Language code e.g. "en", "fr", "yo"
     */
    public String detectLanguage(String text) {
        if (text == null || text.length() < 5) return "en";

        try {
            String payload = String.format(
                "{\"q\":\"%s\"%s}",
                escapeJson(text.substring(0, Math.min(text.length(), 200))),
                apiKey.isBlank() ? "" : ",\"api_key\":\"" + apiKey + "\""
            );

            String response = httpPost(apiUrl + "/detect", payload);

            // Parse first result: [{"confidence":0.9,"language":"en"},...]
            int langIdx = response.indexOf("\"language\":\"");
            if (langIdx == -1) return "en";
            int start = langIdx + 12;
            int end = response.indexOf("\"", start);
            return response.substring(start, end);

        } catch (Exception e) {
            logger.warning("Language detection failed: " + e.getMessage());
            return "en";
        }
    }

    /**
     * Returns list of supported language codes.
     */
    public java.util.List<LanguageInfo> getSupportedLanguages() {
        try {
            String response = httpGet(apiUrl + "/languages");
            return parseLanguages(response);
        } catch (Exception e) {
            logger.warning("Failed to fetch languages: " + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    // =========================================================================
    // HTTP client (pure Java - no external deps)
    // =========================================================================

    private String callLibreTranslate(String text,
                                       String sourceLang,
                                       String targetLang) throws IOException {

        String payload = String.format(
            "{\"q\":\"%s\",\"source\":\"%s\",\"target\":\"%s\"%s}",
            escapeJson(text),
            sourceLang != null ? sourceLang : "auto",
            targetLang,
            apiKey.isBlank() ? "" : ",\"api_key\":\"" + apiKey + "\""
        );

        String response = httpPost(apiUrl + "/translate", payload);

        // Parse: {"translatedText": "..."}
        int idx = response.indexOf("\"translatedText\":\"");
        if (idx == -1) return text;

        int start = idx + 18;
        // Find closing quote, handling escaped quotes
        int end = start;
        while (end < response.length()) {
            char c = response.charAt(end);
            if (c == '"' && response.charAt(end - 1) != '\\') break;
            end++;
        }

        return unescapeJson(response.substring(start, end));
    }

    private String httpPost(String urlStr, String payload) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        try (InputStream is = conn.getResponseCode() < 400
                ? conn.getInputStream()
                : conn.getErrorStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);

        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private java.util.List<LanguageInfo> parseLanguages(String json) {
        java.util.List<LanguageInfo> languages = new java.util.ArrayList<>();
        int pos = 0;
        while (true) {
            int codeIdx = json.indexOf("\"code\":\"", pos);
            if (codeIdx == -1) break;
            int codeStart = codeIdx + 8;
            int codeEnd = json.indexOf("\"", codeStart);

            int nameIdx = json.indexOf("\"name\":\"", pos);
            int nameStart = nameIdx + 8;
            int nameEnd = json.indexOf("\"", nameStart);

            if (codeEnd == -1 || nameEnd == -1) break;

            languages.add(new LanguageInfo(
                    json.substring(codeStart, codeEnd),
                    json.substring(nameStart, nameEnd)
            ));

            pos = Math.max(codeEnd, nameEnd);
        }
        return languages;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    public void shutdown() {
        executor.shutdown();
    }

    public record LanguageInfo(String code, String name) {}
}