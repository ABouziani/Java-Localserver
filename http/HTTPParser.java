package http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

public class HTTPParser {

    public static long CLIENT_MAX_BODY = 10485760; 
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final int MAX_HEADERS_SIZE = 16384;
    private static final int MAX_URI_LENGTH = 8192;

    public static HTTPRequest tryParse(byte[] rawBytes) {

        int headersEnd = findHeadersEnd(rawBytes);
        if (headersEnd == -1) {
            return null;
        }

        byte[] headersBytes = Arrays.copyOfRange(rawBytes, 0, headersEnd);
        String headersSection = new String(headersBytes, StandardCharsets.UTF_8);

        if (headersSection.length() > MAX_HEADERS_SIZE) {
            return HTTPRequest.invalid(431, "Request header fields too large");
        }

        String[] lines = headersSection.split("\r\n");

        if (lines.length == 0) {
            return HTTPRequest.invalid(400, "Empty request");
        }

        String requestLine = lines[0];

        if (requestLine.startsWith(" ") || requestLine.endsWith(" ")) {
            return HTTPRequest.invalid(400, "Request line has leading or trailing spaces");
        }

        if (requestLine.length() > MAX_REQUEST_LINE_LENGTH) {
            return HTTPRequest.invalid(431, "Request line too long");
        }

        if (requestLine.contains("  ")) {
            return HTTPRequest.invalid(400, "Request line contains multiple spaces");
        }

        String[] parts = requestLine.split(" ");

        if (parts.length != 3) {
            return HTTPRequest.invalid(400, "Invalid request line format");
        }

        if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return HTTPRequest.invalid(400, "Request line has empty components");
        }

        String method = parts[0];
        String rawPath = parts[1];
        String version = parts[2];

        if (!isValidMethod(method)) {
            return HTTPRequest.invalid(400, "Invalid method format");
        }

        if (!method.equals("GET") &&
                !method.equals("POST") &&
                !method.equals("DELETE")) {
            return HTTPRequest.invalid(501, "Method not implemented: " + method);
        }

        if (!rawPath.startsWith("/")) {
            return HTTPRequest.invalid(400, "Request target must start with /");
        }

        if (rawPath.length() > MAX_URI_LENGTH) {
            return HTTPRequest.invalid(414, "URI too long");
        }

        if (rawPath.contains(" ") || rawPath.contains("\t")) {
            return HTTPRequest.invalid(400, "Request target contains unencoded whitespace");
        }

        if (rawPath.contains("\r") || rawPath.contains("\n") || rawPath.contains("\0")) {
            return HTTPRequest.invalid(400, "Request target contains control characters");
        }

        if (!version.matches("HTTP/\\d\\.\\d")) {
            return HTTPRequest.invalid(400, "Invalid HTTP version format");
        }

        if (!version.equals("HTTP/1.1")) {
            return HTTPRequest.invalid(505, "HTTP version not supported: " + version);
        }

        Map<String, String> headers = new HashMap<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];

            if (line.length() > MAX_HEADER_LINE_LENGTH) {
                return HTTPRequest.invalid(431, "Header line too long");
            }

            if (line.startsWith(" ") || line.startsWith("\t")) {
                return HTTPRequest.invalid(400, "Obsolete line folding detected");
            }

            line = line.trim();
            if (line.isEmpty())
                continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx == -1) {
                return HTTPRequest.invalid(400, "Invalid header format: missing colon");
            }

            if (colonIdx == 0) {
                return HTTPRequest.invalid(400, "Invalid header: empty field name");
            }

            String name = line.substring(0, colonIdx);
            String value = line.substring(colonIdx + 1).trim();

            if (name.trim().length() != name.length()) {
                return HTTPRequest.invalid(400, "Header name contains whitespace");
            }

            if (!isValidHeaderName(name)) {
                return HTTPRequest.invalid(400, "Invalid header field name: " + name);
            }

            if (value.contains("\r") || value.contains("\n") || value.contains("\0")) {
                return HTTPRequest.invalid(400, "Invalid header field value");
            }

            String normalizedName = name.toLowerCase();

            if (headers.containsKey(normalizedName)) {
                headers.put(normalizedName, headers.get(normalizedName) + ", " + value);
            } else {
                headers.put(normalizedName, value);
            }
        }

        if (version.equals("HTTP/1.1")) {
            if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
                return HTTPRequest.invalid(400, "Missing Host header (required for HTTP/1.1)");
            }
        }

        long contentLength = -1;
        boolean isChunked = false;

        if (headers.containsKey("transfer-encoding")) {
            String te = headers.get("transfer-encoding").toLowerCase();
            if (te.endsWith("chunked")) {
                isChunked = true;
            } else if (te.contains("chunked")) {
                return HTTPRequest.invalid(400, "chunked must be the final Transfer-Encoding");
            }
        }

        if (headers.containsKey("content-length")) {
            if (isChunked) {
                return HTTPRequest.invalid(400, "Both Content-Length and chunked Transfer-Encoding present");
            }

            String clValue = headers.get("content-length");

            if (clValue.contains(",")) {
                String[] values = clValue.split(",");
                String firstValue = values[0].trim();

                for (String v : values) {
                    if (!v.trim().equals(firstValue)) {
                        return HTTPRequest.invalid(400, "Multiple different Content-Length values");
                    }
                }
                clValue = firstValue;
            }

            try {
                contentLength = Long.parseLong(clValue.trim());
                if (contentLength < 0) {
                    return HTTPRequest.invalid(400, "Negative Content-Length");
                }
            } catch (NumberFormatException e) {
                return HTTPRequest.invalid(400, "Invalid Content-Length value");
            }
        }

        byte[] body = null;
        byte[] remaining = new byte[0];
        int bodyStart = headersEnd + 4;

        if (method.equals("POST")) {
            if (!isChunked && contentLength == -1) {
                return HTTPRequest.invalid(411, "POST requires Content-Length or Transfer-Encoding: chunked");
            }

            if (isChunked) {
                ChunkResult result = parseChunkedBody(rawBytes, bodyStart);
                if (result == null) {
                    return null; 
                }

                body = result.body;
                remaining = result.remaining;

                if (body.length > CLIENT_MAX_BODY) {
                    return HTTPRequest.invalid(413, "Request body too large");
                }

            } else {
                if (contentLength > CLIENT_MAX_BODY) {
                    return HTTPRequest.invalid(413, "Content-Length exceeds maximum allowed size");
                }

                if (bodyStart + contentLength > rawBytes.length) {
                    return null; // Incomplete body
                }

                body = Arrays.copyOfRange(rawBytes, bodyStart, bodyStart + (int) contentLength);

                if (bodyStart + contentLength < rawBytes.length) {
                    remaining = Arrays.copyOfRange(rawBytes, bodyStart + (int) contentLength, rawBytes.length);
                }
            }

        } else {
            body = null;
            if (bodyStart < rawBytes.length) {
                remaining = Arrays.copyOfRange(rawBytes, bodyStart, rawBytes.length);
            }

            if (isChunked || contentLength > 0) {
                return HTTPRequest.invalid(400, method + " request must not have a body");
            }
        }

        HTTPRequest request = new HTTPRequest();

        request.method = method;

        try {
            request.path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            request.path = rawPath;
        }

        request.version = version;
        request.headers = headers;
        request.body = body;
        request.valid = true;
        request.remain = remaining;

        return request;
    }

   
    private static int findHeadersEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' &&
                    data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

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

    
    private static ChunkResult parseChunkedBody(byte[] data, int startPos) {
        byte[] fullBody = new byte[0];
        int position = startPos;

        try {
            while (position < data.length) {
                int lineEnd = findCRLF(data, position);
                if (lineEnd == -1) {
                    return null; 
                }

                String chunkSizeLine = new String(
                        Arrays.copyOfRange(data, position, lineEnd),
                        StandardCharsets.UTF_8).trim();

                int semicolonPos = chunkSizeLine.indexOf(';');
                if (semicolonPos != -1) {
                    chunkSizeLine = chunkSizeLine.substring(0, semicolonPos).trim();
                }

                if (chunkSizeLine.isEmpty()) {
                    return null; // Invalid chunk
                }

                long chunkSize;
                try {
                    chunkSize = Long.parseLong(chunkSizeLine, 16);
                } catch (NumberFormatException e) {
                    return null; // Invalid chunk size
                }

                if (chunkSize < 0) {
                    return null;
                }

                if (chunkSize == 0) {
                    position = lineEnd + 2;

                    int finalEnd = findDoubleCRLF(data, position);
                    if (finalEnd == -1) {
                        return null; // Incomplete
                    }

                    byte[] remaining = new byte[0];
                    if (finalEnd + 4 < data.length) {
                        remaining = Arrays.copyOfRange(data, finalEnd + 4, data.length);
                    }

                    return new ChunkResult(fullBody, remaining);
                }

                position = lineEnd + 2;

                if (position + chunkSize + 2 > data.length) {
                    return null; // Incomplete chunk
                }

                byte[] chunkData = Arrays.copyOfRange(data, position, position + (int) chunkSize);
                fullBody = concatenateBytes(fullBody, chunkData);

                if (fullBody.length > CLIENT_MAX_BODY) {
                    return null; // Body too large
                }

                position += (int) chunkSize + 2; // Skip chunk data + CRLF
            }

            return null; 

        } catch (Exception e) {
            return null; // Parse error
        }
    }

    
    private static int findCRLF(byte[] data, int start) {
        for (int i = start; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

   
    private static int findDoubleCRLF(byte[] data, int start) {
        for (int i = start; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' &&
                    data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

   
    private static byte[] concatenateBytes(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    static class ChunkResult {
        byte[] body;
        byte[] remaining;

        ChunkResult(byte[] body, byte[] remaining) {
            this.body = body;
            this.remaining = remaining;
        }
    }
}