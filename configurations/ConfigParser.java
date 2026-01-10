package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ConfigParser {

    public static Map<String, Object> parse(String s) {
        // CHANGE 1: Added protection against null or empty input strings
        if (s == null || s.trim().isEmpty()) {
            throw new ConfigParseException("Empty input", 0);
        }
        
        Object result = new Parser(s).parseValue();
        
        if (!(result instanceof Map)) {
            throw new ConfigParseException("Error: Config must start with an object '{'!", 0);
        }
        return (Map<String, Object>) result;
    }

    public static class ConfigParseException extends RuntimeException {
        public ConfigParseException(String message, int position) {
            super(message + " at position " + position);
        }
        public ConfigParseException(String message, int position, Throwable cause) {
            super(message + " at position " + position, cause);
        }
    }

    static class Parser {
        private final String rawText;
        private final int configLen;
        private int i = 0;

        Parser(String s) {
            this.rawText = s;
            this.configLen = rawText.length();
        }

        private void skipSpaces() {
            while (i < configLen && Character.isWhitespace(rawText.charAt(i))) {
                i++;
            }
        }

        private ConfigParseException error(String msg) {
            return new ConfigParseException(msg, i);
        }

        private ConfigParseException error(String msg, Throwable cause) {
            return new ConfigParseException(msg, i, cause);
        }

        Object parseValue() {
            skipSpaces();
            // CHANGE 2: Bounds check to prevent crashing on trailing whitespace
            if (i >= configLen) throw error("Unexpected end of input");

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
            i++; // skip '{'
            skipSpaces();

            while (i < configLen && rawText.charAt(i) != '}') {
                if (rawText.charAt(i) != '"') throw error("Expected string key");

                String key = parseString();
                skipSpaces();

                if (i >= configLen || rawText.charAt(i) != ':') {
                    throw error("Expected ':' after key '" + key + "'");
                }
                i++; // skip ':'

                map.put(key, parseValue());
                skipSpaces();

                // CHANGE 4: Improved comma logic to handle sequences correctly and prevent out-of-bounds
                if (i < configLen && rawText.charAt(i) == ',') {
                    i++;
                    skipSpaces();
                    // Detect illegal trailing commas like {"a": 1, }
                    if (i < configLen && rawText.charAt(i) == '}') {
                        throw error("Trailing comma not allowed");
                    }
                } else if (i < configLen && rawText.charAt(i) != '}') {
                    throw error("Expected ',' or '}'");
                }
            }

            // CHANGE 5: Ensure the object is properly closed
            if (i >= configLen || rawText.charAt(i) != '}') throw error("Missing '}'");
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

                // CHANGE 4 (continued): Safe comma handling for arrays
                if (i < configLen && rawText.charAt(i) == ',') {
                    i++;
                    skipSpaces();
                    if (i < configLen && rawText.charAt(i) == ']') {
                        throw error("Trailing comma not allowed");
                    }
                } else if (i < configLen && rawText.charAt(i) != ']') {
                    throw error("Expected ',' or ']'");
                }
            }

            if (i >= configLen || rawText.charAt(i) != ']') throw error("Missing ']'");
            i++; // skip ']'
            return list;
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            i++; // skip opening '"'

            while (i < configLen) {
                char c = rawText.charAt(i++);
                if (c == '"') return sb.toString();

                // CHANGE 3: Implemented full support for escape characters instead of throwing error
                if (c == '\\') {
                    if (i >= configLen) throw error("Unterminated escape sequence");
                    char next = rawText.charAt(i++);
                    switch (next) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        default:   sb.append(next); 
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        Object parseBool() {
            if (rawText.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (rawText.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw error("Invalid boolean value");
        }

        Object parseNull() {
            if (rawText.startsWith("null", i)) { i += 4; return null; }
            throw error("Invalid null value");
        }

        Number parseNumber() {
            int start = i;
            // CHANGE 6: Added support for signs (+/-) at the start of numbers
            if (i < configLen && (rawText.charAt(i) == '+' || rawText.charAt(i) == '-')) i++;
            
            while (i < configLen && "0123456789.eE+-".indexOf(rawText.charAt(i)) >= 0) {
                i++;
            }

            String token = rawText.substring(start, i);
            if (token.isEmpty()) throw error("Expected number");

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