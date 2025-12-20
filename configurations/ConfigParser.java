package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ConfigParser {

    // ===== Public API =====
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String s) {
        Object result = new Parser(s).parseValue();
        if (!(result instanceof Map)) {
            throw new ConfigParseException("Error: Config file format error!", 0);
        }
        return (Map<String, Object>) result;
    }

    // ===== Custom Exception =====
    public static class ConfigParseException extends RuntimeException {
        public ConfigParseException(String message, int position) {
            super(message + " at position " + position);
        }

        public ConfigParseException(String message, int position, Throwable cause) {
            super(message + " at position " + position, cause);
        }
    }

    // ===== Parser =====
    static class Parser {
        private final String rawText;
        private final int configLen;
        private int i = 0;

        Parser(String s) {
            this.rawText = s;
            this.configLen = rawText.length();
        }

        // ---- helpers ----
        void skipSpaces() {
            while (i < configLen && Character.isWhitespace(rawText.charAt(i))) {
                i++;
            }
        }

        ConfigParseException error(String msg) {
            return new ConfigParseException(msg, i);
        }

        ConfigParseException error(String msg, Throwable cause) {
            return new ConfigParseException(msg, i, cause);
        }

        // ---- parsing ----
        Object parseValue() {
            skipSpaces();

            if (i >= configLen) {
                throw error("Unexpected end of input");
            }

            char c = rawText.charAt(i);

            if (c == '{') return parseObj();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') return parseNull();

            return parseNumber();
        }

        Map<String, Object> parseObj() {
            Map<String, Object> map = new HashMap<>();
            i++;
            skipSpaces();
            while (i < configLen && rawText.charAt(i) != '}') {

                if (rawText.charAt(i) != '"') {
                    throw error("Expected string key");
                }

                String key = parseString();
                skipSpaces();

                if (i >= configLen || rawText.charAt(i) != ':') {
                    throw error("Expected ':' after key");
                }
                i++; // skip ':'

                Object value = parseValue();
                if (map.put(key, value) != null) {
                    throw error("Duplicate key: " + key);
                }
                skipSpaces();

                if (rawText.charAt(i) == ',') {
                    if (i < configLen) {
                        i++;
                        skipSpaces();
                        if (rawText.charAt(i) == '}') {   
                             throw error("',' Unexpected ");
                        }
                    }
                } else if (rawText.charAt(i) == '}') {
                    break;
                } else {
                    throw error("Expected ',' or '}'");
                }
            }

            if (i >= configLen) {
                throw error("Missing '}'");
            }

            i++; // skip '}'
            return map;
        }

        ArrayList<Object> parseArray() {
            ArrayList<Object> list = new ArrayList<>();
            i++; // skip '['
            skipSpaces();

            while (i < configLen && rawText.charAt(i) != ']') {
                list.add(parseValue());
                skipSpaces();

                if (rawText.charAt(i) == ',') {
                    if (i < configLen) {
                        i++;
                        skipSpaces();
                        if (rawText.charAt(i) == ']') {   
                             throw error("',' Unexpected ");
                        }
                    }
                } else if (rawText.charAt(i) == ']') {
                    break;
                } else {
                    throw error("Expected ',' or ']'");
                }
            }

            if (i >= configLen) {
                throw error("Missing ']'");
            }

            i++; // skip ']'
            return list;
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            i++; // skip '"'

            while (i < configLen) {
                char c = rawText.charAt(i++);

                if (c == '"') {
                    return sb.toString();
                }

                if (c == '\\') {
                   // if (i >= configLen) {
                        throw error("Invalid config format");
                    //}
                    //sb.append(rawText.charAt(i++));
                } else {
                    sb.append(c);
                }
            }

            throw error("Unterminated string");
        }

        Object parseBool() {
            if (rawText.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (rawText.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw error("Invalid boolean value");
        }

        Object parseNull() {
            if (rawText.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw error("Invalid null value");
        }

        Number parseNumber() {
            int start = i;

            // number must start with one of these
            if (i >= configLen || "+-0123456789".indexOf(rawText.charAt(i)) == -1) {
                throw error("Expected number");
            }

            while (i < configLen && "0123456789+-.eE".indexOf(rawText.charAt(i)) >= 0) {
                i++;
            }

            String token = rawText.substring(start, i);

            if (token.isEmpty()) {
                throw error("Empty number");
            }

            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw error("Invalid number format: " + token, e);
            }
        }
    }
}
