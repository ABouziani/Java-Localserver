package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HTTPParser {

    public static final long CLIENT_MAX_BODY = 104857600; // 100 MB hard cap
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final int MAX_HEADERS_SIZE = 16384;
    private static final int MAX_URI_LENGTH = 8192;
    private static final byte[] DOUBLE_CRLF = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Parses HTTP request headers only.
     * Returns HTTPRequest with metadata (may need streaming), or null if
     * incomplete/invalid.
     * Advances buffer position to start of body (or end of headers if no body).
     */
    public static HTTPRequest tryParse(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return null;
        }

        buffer.mark();
        int startPos = buffer.position();

        try {
            // Find end of headers (\r\n\r\n)
            int headersEnd = findPattern(buffer, DOUBLE_CRLF);
            if (headersEnd == -1) {
                buffer.reset();
                return null; // Incomplete headers
            }

            int headersLength = headersEnd - startPos;
            if (headersLength > MAX_HEADERS_SIZE) {
                return HTTPRequest.invalid(431, "Request header fields too large");
            }

            // Extract headers section
            byte[] headersBytes = new byte[headersLength];
            buffer.position(startPos);
            buffer.get(headersBytes);
            String headersSection = new String(headersBytes, StandardCharsets.UTF_8);

            // Skip \r\n\r\n — position now at start of body
            buffer.position(headersEnd + 4);

            // Split into lines
            String[] lines = headersSection.split("\r\n");
            if (lines.length == 0) {
                return HTTPRequest.invalid(400, "Empty request");
            }

            // Parse request line
            String requestLine = lines[0];
            if (requestLine.startsWith(" ") || requestLine.endsWith(" ")) {
                return HTTPRequest.invalid(400, "Request line has leading/trailing spaces");
            }
            if (requestLine.length() > MAX_REQUEST_LINE_LENGTH) {
                return HTTPRequest.invalid(431, "Request line too long");
            }
            if (requestLine.contains("  ")) {
                return HTTPRequest.invalid(400, "Request line contains multiple spaces");
            }

            String[] parts = requestLine.split(" ", 4);
            if (parts.length != 3) {
                return HTTPRequest.invalid(400, "Invalid request line format");
            }
            if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                return HTTPRequest.invalid(400, "Request line has empty components");
            }

            String method = parts[0];
            String rawPath = parts[1];
            String version = parts[2];

            // Validate method
            if (!isValidMethod(method)) {
                return HTTPRequest.invalid(400, "Invalid method format");
            }
            if (!method.equals("GET") && !method.equals("POST") && !method.equals("DELETE")) {
                return HTTPRequest.invalid(501, "Method not implemented: " + method);
            }

            // Validate path
            if (!rawPath.startsWith("/")) {
                return HTTPRequest.invalid(400, "Request target must start with /");
            }
            if (rawPath.length() > MAX_URI_LENGTH) {
                return HTTPRequest.invalid(414, "URI too long");
            }
            if (rawPath.contains(" ") || rawPath.contains("\t") ||
                    rawPath.contains("\r") || rawPath.contains("\n") || rawPath.contains("\0")) {
                return HTTPRequest.invalid(400, "Invalid characters in request target");
            }

            // Validate version
            if (!version.matches("HTTP/\\d\\.\\d")) {
                return HTTPRequest.invalid(400, "Invalid HTTP version format");
            }
            if (!version.equals("HTTP/1.1")) {
                return HTTPRequest.invalid(505, "HTTP version not supported: " + version);
            }

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty())
                    continue;

                if (line.length() > MAX_HEADER_LINE_LENGTH) {
                    return HTTPRequest.invalid(431, "Header line too long");
                }
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    return HTTPRequest.invalid(400, "Obsolete line folding detected");
                }

                int colonIdx = line.indexOf(':');
                if (colonIdx == -1) {
                    return HTTPRequest.invalid(400, "Invalid header format: missing colon");
                }
                if (colonIdx == 0) {
                    return HTTPRequest.invalid(400, "Invalid header: empty field name");
                }

                String name = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();

                if (!isValidHeaderName(name)) {
                    return HTTPRequest.invalid(400, "Invalid header field name: " + name);
                }
                if (value.contains("\r") || value.contains("\n") || value.contains("\0")) {
                    return HTTPRequest.invalid(400, "Invalid header field value");
                }

                String normalizedName = name.toLowerCase();
                headers.put(normalizedName, headers.containsKey(normalizedName)
                        ? headers.get(normalizedName) + ", " + value
                        : value);
            }

            // Validate Host header
            if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
                return HTTPRequest.invalid(400, "Missing Host header (required for HTTP/1.1)");
            }

            // Parse Content-Length and Transfer-Encoding
            long contentLength = -1;
            boolean isChunked = false;
            boolean isMultipart = false;

            // Check Transfer-Encoding first
            if (headers.containsKey("transfer-encoding")) {
                String te = headers.get("transfer-encoding").toLowerCase();
                if (te.contains("chunked")) {
                    isChunked = true;
                }
            }

            // Check Content-Length
            if (headers.containsKey("content-length")) {
                if (isChunked) {
                    return HTTPRequest.invalid(400, "Both Content-Length and chunked Transfer-Encoding present");
                }

                String clValue = headers.get("content-length");
                if (clValue.contains(",")) {
                    String[] values = clValue.split(",");
                    String first = values[0].trim();
                    for (String v : values) {
                        if (!v.trim().equals(first)) {
                            return HTTPRequest.invalid(400, "Multiple different Content-Length values");
                        }
                    }
                    clValue = first;
                }

                try {
                    contentLength = Long.parseLong(clValue.trim());
                    if (contentLength < 0) {
                        return HTTPRequest.invalid(400, "Negative Content-Length");
                    }
                    if (contentLength > CLIENT_MAX_BODY) {
                        return HTTPRequest.invalid(413, "Content-Length exceeds maximum allowed size");
                    }
                } catch (NumberFormatException e) {
                    return HTTPRequest.invalid(400, "Invalid Content-Length value");
                }
            }

            // Check Content-Type for multipart
            if (headers.containsKey("content-type")) {
                String ct = headers.get("content-type").toLowerCase();
                if (ct.contains("multipart/form-data")) {
                    isMultipart = true;
                }
            }

            // Reject chunked encoding (defer to future work)
            // if (isChunked) {
            // return HTTPRequest.invalid(501, "Transfer-Encoding: chunked not supported");
            // }

            // Decide streaming: POST + (large or multipart)
            boolean needsStreaming = false;
            if (method.equals("POST")) {
                if (isMultipart || (contentLength > 1024 * 1024) || isChunked) {
                    needsStreaming = true;
                }
            }

            // For non-POST: reject body
            if (!method.equals("POST") && (isChunked || contentLength > 0)) {
                return HTTPRequest.invalid(400, method + " request must not have a body");
            }

            // Build request object (headers only — no body read!)
            HTTPRequest req = new HTTPRequest();
            req.method = method;
            try {
                req.path = java.net.URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                req.path = rawPath;
            }
            req.version = version;
            req.headers = headers;
            req.expectedContentLength = contentLength;
            req.isMultipart = isMultipart;
            req.isChunked = isChunked;
            req.needsStreaming = needsStreaming;
            req.valid = true;

            return req;

        } catch (Exception e) {
            buffer.reset();
            return HTTPRequest.invalid(400, "Parse error: " + e.getMessage());
        }
    }

    /**
     * Finds pattern in buffer starting from current position.
     * Returns absolute position of pattern start, or -1 if not found.
     * Does not modify buffer position.
     */
    private static int findPattern(ByteBuffer buffer, byte[] pattern) {
        int startPos = buffer.position();
        int limit = buffer.limit();

       outer : for (int i = startPos; i <= limit - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buffer.get(i + j) != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // Helper methods (unchanged)
    private static boolean isValidMethod(String method) {
        if (method.isEmpty())
            return false;
        for (char c : method.toCharArray()) {
            if (!isTokenChar(c) || Character.isLowerCase(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidHeaderName(String name) {
        if (name.isEmpty())
            return false;
        for (char c : name.toCharArray()) {
            if (!isTokenChar(c))
                return false;
        }
        return true;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') ||
                c == '!' || c == '#' || c == '$' || c == '%' || c == '&' ||
                c == '\'' || c == '*' || c == '+' || c == '-' || c == '.' ||
                c == '^' || c == '_' || c == '`' || c == '|' || c == '~';
    }
}