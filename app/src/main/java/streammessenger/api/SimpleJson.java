package streammessenger.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and builder - no external dependencies.
 *
 * Supports:
 *   String, Number, Boolean, Null, Object, Array
 *
 * Usage:
 *   SimpleJson obj = SimpleJson.parse("{\"key\":\"value\"}");
 *   String val = obj.getString("key");
 *
 *   String json = SimpleJson.builder()
 *       .put("name", "Alice")
 *       .put("age", 30)
 *       .build();
 */
public final class SimpleJson {

    private final Map<String, Object> data;

    private SimpleJson(Map<String, Object> data) {
        this.data = data;
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    /**
     * Parses a JSON object string into a SimpleJson instance.
     *
     * @param json Raw JSON string
     * @return Parsed SimpleJson
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public static SimpleJson parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON string is null or empty");
        }

        json = json.trim();

        if (!json.startsWith("{")) {
            throw new IllegalArgumentException(
                "Expected JSON object starting with '{', got: "
                + json.charAt(0));
        }

        Parser parser = new Parser(json);
        Map<String, Object> result = parser.parseObject();
        return new SimpleJson(result);
    }

    /**
     * Parses a JSON array string into a List.
     */
    public static List<Object> parseArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[")) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        Parser parser = new Parser(json);
        return parser.parseArray();
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getString(String key) {
        Object val = data.get(key);
        if (val == null) return null;
        return String.valueOf(val);
    }

    public String getString(String key, String defaultValue) {
        String val = getString(key);
        return val != null ? val : defaultValue;
    }

    public Integer getInt(String key) {
        Object val = data.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(String.valueOf(val)); }
        catch (NumberFormatException e) { return null; }
    }

    public Long getLong(String key) {
        Object val = data.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(String.valueOf(val)); }
        catch (NumberFormatException e) { return null; }
    }

    public Boolean getBoolean(String key) {
        Object val = data.get(key);
        if (val == null) return null;
        if (val instanceof Boolean) return (Boolean) val;
        return Boolean.parseBoolean(String.valueOf(val));
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object val = data.get(key);
        if (val == null) return new ArrayList<>();
        if (val instanceof List) {
            List<?> list = (List<?>) val;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(String.valueOf(item));
            }
            return result;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public SimpleJson getObject(String key) {
        Object val = data.get(key);
        if (val instanceof Map) {
            return new SimpleJson((Map<String, Object>) val);
        }
        return null;
    }

    public boolean hasKey(String key) {
        return data.containsKey(key);
    }

    public boolean isNull(String key) {
        return data.containsKey(key) && data.get(key) == null;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, Object> data = new LinkedHashMap<>();

        public Builder put(String key, String value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, Number value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, Boolean value) {
            data.put(key, value);
            return this;
        }

        public Builder putNull(String key) {
            data.put(key, null);
            return this;
        }

        public Builder put(String key, List<?> value) {
            data.put(key, value);
            return this;
        }

        public Builder put(String key, Builder nestedBuilder) {
            data.put(key, nestedBuilder.data);
            return this;
        }

        public Builder putRaw(String key, String rawJson) {
            // Store raw JSON as a special marker to avoid double-encoding
            data.put(key, new RawJson(rawJson));
            return this;
        }

        /**
         * Builds the JSON string.
         */
        public String build() {
            return serializeObject(data);
        }

        @Override
        public String toString() {
            return build();
        }
    }

    // =========================================================================
    // Serialization
    // =========================================================================

    @Override
    public String toString() {
        return serializeObject(data);
    }

    @SuppressWarnings("unchecked")
    private static String serializeObject(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;

            sb.append("\"").append(escapeString(entry.getKey())).append("\":");
            sb.append(serializeValue(entry.getValue()));
        }

        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String serializeValue(Object value) {
        if (value == null)          return "null";
        if (value instanceof RawJson) return ((RawJson) value).json;
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number)  return value.toString();
        if (value instanceof String) {
            return "\"" + escapeString((String) value) + "\"";
        }
        if (value instanceof Map) {
            return serializeObject((Map<String, Object>) value);
        }
        if (value instanceof List) {
            return serializeList((List<?>) value);
        }
        return "\"" + escapeString(value.toString()) + "\"";
    }

    private static String serializeList(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(serializeValue(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    // =========================================================================
    // Parser (recursive descent)
    // =========================================================================

    private static final class Parser {

        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos   = 0;
        }

        Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            consume('{');
            skipWhitespace();

            if (peek() == '}') {
                pos++;
                return result;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                consume(':');
                skipWhitespace();
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();

                char next = peek();
                if (next == '}') { pos++; break; }
                if (next == ',') { pos++; continue; }

                throw new IllegalArgumentException(
                    "Expected ',' or '}' at position " + pos
                    + ", got: '" + next + "'");
            }

            return result;
        }

        List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            consume('[');
            skipWhitespace();

            if (peek() == ']') {
                pos++;
                return result;
            }

            while (true) {
                skipWhitespace();
                result.add(parseValue());
                skipWhitespace();

                char next = peek();
                if (next == ']') { pos++; break; }
                if (next == ',') { pos++; continue; }

                throw new IllegalArgumentException(
                    "Expected ',' or ']' at position " + pos);
            }

            return result;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }

            char c = peek();

            if (c == '"')  return parseString();
            if (c == '{')  return parseObject();
            if (c == '[')  return parseArray();
            if (c == 't')  return parseLiteral("true",  Boolean.TRUE);
            if (c == 'f')  return parseLiteral("false", Boolean.FALSE);
            if (c == 'n')  return parseLiteral("null",  null);
            if (c == '-' || Character.isDigit(c)) return parseNumber();

            throw new IllegalArgumentException(
                "Unexpected character '" + c + "' at position " + pos);
        }

        String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();

            while (pos < input.length()) {
                char c = input.charAt(pos++);

                if (c == '"') break;

                if (c == '\\') {
                    if (pos >= input.length()) break;
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            /* Unicode escape: \\uXXXX*/
                            if (pos + 4 > input.length()) break;
                            String hex = input.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }

            return sb.toString();
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;

            while (pos < input.length()
                    && Character.isDigit(input.charAt(pos))) pos++;

            boolean isDecimal = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < input.length()
                        && Character.isDigit(input.charAt(pos))) pos++;
            }

            if (pos < input.length()
                    && (input.charAt(pos) == 'e'
                        || input.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < input.length()
                        && (input.charAt(pos) == '+'
                            || input.charAt(pos) == '-')) pos++;
                while (pos < input.length()
                        && Character.isDigit(input.charAt(pos))) pos++;
            }

            String numStr = input.substring(start, pos);
            try {
                if (isDecimal) return Double.parseDouble(numStr);
                long val = Long.parseLong(numStr);
                // Return as int if it fits
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    return (int) val;
                }
                return val;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Invalid number: " + numStr);
            }
        }

        Object parseLiteral(String expected, Object value) {
            if (input.startsWith(expected, pos)) {
                pos += expected.length();
                return value;
            }
            throw new IllegalArgumentException(
                "Expected '" + expected + "' at position " + pos);
        }

        void consume(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new IllegalArgumentException(
                    "Expected '" + expected + "' at position " + pos
                    + " but got '"
                    + (pos < input.length() ? input.charAt(pos) : "EOF")
                    + "'");
            }
            pos++;
        }

        char peek() {
            if (pos >= input.length()) return 0;
            return input.charAt(pos);
        }

        void skipWhitespace() {
            while (pos < input.length()
                    && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }

    // Marker class for pre-serialized JSON
    private record RawJson(String json) {}
}