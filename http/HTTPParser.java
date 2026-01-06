package http;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HTTPParser {

    public static long CLIENT_MAX_BODY = 1048576000; // 10MB
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final int MAX_HEADERS_SIZE = 16384;
    private static final int MAX_URI_LENGTH = 8192;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOUBLE_CRLF = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Parse headers only from byte array.
     * Returns ParseResult with request and bytes consumed.
     */
    public static ParseResult parseHeaders(byte[] data, int offset, int length) {
        ParseResult result = new ParseResult();
        
        if (data == null || length == 0) {
            result.complete = false;
            return result;
        }
        
        try {
            // Find end of headers (\r\n\r\n)
            int headersEnd = findPattern(data, offset, length, DOUBLE_CRLF);
            if (headersEnd == -1) {
                result.complete = false;
                return result; // Incomplete headers
            }
            
            int headersLength = headersEnd - offset;
            if (headersLength > MAX_HEADERS_SIZE) {
                result.request = HTTPRequest.invalid(431, "Request header fields too large");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            // Extract headers as string
            String headersSection = new String(data, offset, headersLength, StandardCharsets.UTF_8);
            
            // Parse headers section
            String[] lines = headersSection.split("\r\n");
            if (lines.length == 0) {
                result.request = HTTPRequest.invalid(400, "Empty request");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            // Parse request line
            String requestLine = lines[0];
            
            if (requestLine.startsWith(" ") || requestLine.endsWith(" ")) {
                result.request = HTTPRequest.invalid(400, "Request line has leading or trailing spaces");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (requestLine.length() > MAX_REQUEST_LINE_LENGTH) {
                result.request = HTTPRequest.invalid(431, "Request line too long");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (requestLine.contains("  ")) {
                result.request = HTTPRequest.invalid(400, "Request line contains multiple spaces");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            String[] parts = requestLine.split(" ", 4);
            if (parts.length != 3) {
                result.request = HTTPRequest.invalid(400, "Invalid request line format");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                result.request = HTTPRequest.invalid(400, "Request line has empty components");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }

            String method = parts[0];
            String rawPath = parts[1];
            String version = parts[2];

            // Validate method
            if (!isValidMethod(method)) {
                result.request = HTTPRequest.invalid(400, "Invalid method format");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (!method.equals("GET") && !method.equals("POST") && !method.equals("DELETE")) {
                result.request = HTTPRequest.invalid(501, "Method not implemented: " + method);
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }

            // Validate path
            if (!rawPath.startsWith("/")) {
                result.request = HTTPRequest.invalid(400, "Request target must start with /");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (rawPath.length() > MAX_URI_LENGTH) {
                result.request = HTTPRequest.invalid(414, "URI too long");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (rawPath.contains(" ") || rawPath.contains("\t")) {
                result.request = HTTPRequest.invalid(400, "Request target contains unencoded whitespace");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (rawPath.contains("\r") || rawPath.contains("\n") || rawPath.contains("\0")) {
                result.request = HTTPRequest.invalid(400, "Request target contains control characters");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }

            // Validate version
            if (!version.matches("HTTP/\\d\\.\\d")) {
                result.request = HTTPRequest.invalid(400, "Invalid HTTP version format");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }
            
            if (!version.equals("HTTP/1.1")) {
                result.request = HTTPRequest.invalid(505, "HTTP version not supported: " + version);
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                
                if (line.length() > MAX_HEADER_LINE_LENGTH) {
                    result.request = HTTPRequest.invalid(431, "Header line too long");
                    result.complete = true;
                    result.bytesConsumed = headersLength + 4;
                    return result;
                }
                
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    result.request = HTTPRequest.invalid(400, "Obsolete line folding detected");
                    result.complete = true;
                    result.bytesConsumed = headersLength + 4;
                    return result;
                }
                
                line = line.trim();
                if (line.isEmpty()) continue;
                
                int colonIdx = line.indexOf(':');
                if (colonIdx == -1) {
                    result.request = HTTPRequest.invalid(400, "Invalid header format: missing colon");
                    result.complete = true;
                    result.bytesConsumed = headersLength + 4;
                    return result;
                }
                
                if (colonIdx == 0) {
                    result.request = HTTPRequest.invalid(400, "Invalid header: empty field name");
                    result.complete = true;
                    result.bytesConsumed = headersLength + 4;
                    return result;
                }
                
                String name = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                
                if (!isValidHeaderName(name)) {
                    result.request = HTTPRequest.invalid(400, "Invalid header field name: " + name);
                    result.complete = true;
                    result.bytesConsumed = headersLength + 4;
                    return result;
                }
                
                if (value.contains("\r") || value.contains("\n") || value.contains("\0")) {
                    result.request = HTTPRequest.invalid(400, "Invalid header field value");
                    result.complete = true;
                    result.bytesConsumed = headersLength + 4;
                    return result;
                }
                
                String normalizedName = name.toLowerCase();
                
                if (headers.containsKey(normalizedName)) {
                    headers.put(normalizedName, headers.get(normalizedName) + ", " + value);
                } else {
                    headers.put(normalizedName, value);
                }
            }

            // Validate Host header
            if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
                result.request = HTTPRequest.invalid(400, "Missing Host header (required for HTTP/1.1)");
                result.complete = true;
                result.bytesConsumed = headersLength + 4;
                return result;
            }

            // Create request with headers only
            HTTPRequest request = new HTTPRequest();
            request.method = method;
            
            try {
                request.path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                request.path = rawPath;
            }
            
            request.version = version;
            request.headers = headers;
            request.bodyBytes = null;
            request.valid = true;
            
            result.request = request;
            result.complete = true;
            result.bytesConsumed = headersLength + 4; // +4 for \r\n\r\n
            return result;
            
        } catch (Exception e) {
            result.request = HTTPRequest.invalid(400, "Parse error: " + e.getMessage());
            result.complete = true;
            return result;
        }
    }

    /**
     * Parse body for a request that already has headers parsed.
     * Returns ParseResult with updated request and bytes consumed.
     */
    public static ParseResult parseBody(HTTPRequest request, byte[] data, int offset, int length) {
        ParseResult result = new ParseResult();
        result.request = request;
        
        if (request == null || !request.valid) {
            result.complete = true;
            result.bytesConsumed = 0;
            return result;
        }
        
        try {
            Map<String, String> headers = request.headers;
            String method = request.method;
            
            // Parse Content-Length and Transfer-Encoding
            long contentLength = -1;
            boolean isChunked = false;
            
            if (headers.containsKey("transfer-encoding")) {
                String te = headers.get("transfer-encoding").toLowerCase();
                if (te.contains("chunked")) {
                    isChunked = true;
                }
            }
            
            if (headers.containsKey("content-length")) {
                if (isChunked) {
                    result.request = HTTPRequest.invalid(400, "Both Content-Length and chunked Transfer-Encoding present");
                    result.complete = true;
                    result.bytesConsumed = 0;
                    return result;
                }
                
                String clValue = headers.get("content-length");
                
                if (clValue.contains(",")) {
                    String[] values = clValue.split(",");
                    String firstValue = values[0].trim();
                    
                    for (String v : values) {
                        if (!v.trim().equals(firstValue)) {
                            result.request = HTTPRequest.invalid(400, "Multiple different Content-Length values");
                            result.complete = true;
                            result.bytesConsumed = 0;
                            return result;
                        }
                    }
                    clValue = firstValue;
                }
                
                try {
                    contentLength = Long.parseLong(clValue.trim());
                    if (contentLength < 0) {
                        result.request = HTTPRequest.invalid(400, "Negative Content-Length");
                        result.complete = true;
                        result.bytesConsumed = 0;
                        return result;
                    }
                } catch (NumberFormatException e) {
                    result.request = HTTPRequest.invalid(400, "Invalid Content-Length value");
                    result.complete = true;
                    result.bytesConsumed = 0;
                    return result;
                }
            }

            // Parse body
            byte[] body = null;
            int bodyBytesConsumed = 0;
            
            if (method.equals("POST")) {
                if (!isChunked && contentLength == -1) {
                    result.request = HTTPRequest.invalid(411, "POST requires Content-Length or Transfer-Encoding: chunked");
                    result.complete = true;
                    result.bytesConsumed = 0;
                    return result;
                }
                
                if (isChunked) {
                    ChunkResult chunkResult = parseChunkedBodyBytes(data, offset, length);
                    if (chunkResult == null) {
                        result.complete = false;
                        result.bytesConsumed = 0;
                        return result; // Incomplete
                    }
                    
                    body = chunkResult.body;
                    bodyBytesConsumed = chunkResult.bytesConsumed;
                    
                    if (body.length > CLIENT_MAX_BODY) {
                        result.request = HTTPRequest.invalid(413, "Request body too large");
                        result.complete = true;
                        result.bytesConsumed = bodyBytesConsumed;
                        return result;
                    }
                    
                } else {
                    if (contentLength > CLIENT_MAX_BODY) {
                        result.request = HTTPRequest.invalid(413, "Content-Length exceeds maximum allowed size");
                        result.complete = true;
                        result.bytesConsumed = 0;
                        return result;
                    }
                    
                    if (length < contentLength) {
                        result.complete = false;
                        result.bytesConsumed = 0;
                        return result; // Incomplete body
                    }
                    
                    body = new byte[(int) contentLength];
                    System.arraycopy(data, offset, body, 0, (int) contentLength);
                    bodyBytesConsumed = (int) contentLength;
                }
                
            } else {
                // GET, DELETE - no body expected
                if (isChunked || contentLength > 0) {
                    result.request = HTTPRequest.invalid(400, method + " request must not have a body");
                    result.complete = true;
                    result.bytesConsumed = 0;
                    return result;
                }
            }

            // Body parsed successfully
            request.bodyBytes = body;
            result.request = request;
            result.complete = true;
            result.bytesConsumed = bodyBytesConsumed;
            return result;
            
        } catch (Exception e) {
            result.request = HTTPRequest.invalid(400, "Body parse error: " + e.getMessage());
            result.complete = true;
            result.bytesConsumed = 0;
            return result;
        }
    }

    /**
     * Find pattern in byte array.
     */
    private static int findPattern(byte[] data, int offset, int length, byte[] pattern) {
        int end = offset + length - pattern.length;
        
        outer: for (int i = offset; i <= end; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Parse chunked body from byte array.
     */
    private static ChunkResult parseChunkedBodyBytes(byte[] data, int offset, int length) {
        ByteArrayOutputStream bodyAccumulator = new ByteArrayOutputStream();
        int position = offset;
        int end = offset + length;
        
        try {
            while (position < end) {
                // Find chunk size line (\r\n)
                int lineEnd = findPattern(data, position, end - position, CRLF);
                
                if (lineEnd == -1) {
                    return null; // Incomplete
                }
                
                // Read chunk size line
                String chunkSizeLine = new String(data, position, lineEnd - position, StandardCharsets.UTF_8).trim();
                position = lineEnd + 2; // Skip \r\n
                
                // Parse chunk size
                int semicolonPos = chunkSizeLine.indexOf(';');
                if (semicolonPos != -1) {
                    chunkSizeLine = chunkSizeLine.substring(0, semicolonPos).trim();
                }
                
                if (chunkSizeLine.isEmpty()) {
                    continue;
                }
                
                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(chunkSizeLine, 16);
                } catch (NumberFormatException e) {
                    return null;
                }
                
                if (chunkSize < 0) {
                    return null;
                }
                
                // Last chunk
                if (chunkSize == 0) {
                    int trailersEnd = findPattern(data, position, end - position, CRLF);
                    if (trailersEnd == -1) {
                        return null;
                    }
                    
                    if (trailersEnd == position) {
                        position += 2;
                    } else {
                        int finalEnd = findPattern(data, position, end - position, DOUBLE_CRLF);
                        if (finalEnd == -1) {
                            return null;
                        }
                        position = finalEnd + 4;
                    }
                    
                    return new ChunkResult(bodyAccumulator.toByteArray(), position - offset);
                }
                
                // Check if we have enough data for chunk
                if (position + chunkSize + 2 > end) {
                    return null; // Incomplete
                }
                
                // Read chunk data
                bodyAccumulator.write(data, position, chunkSize);
                position += chunkSize + 2; // Skip data + \r\n
            }
            
            return null; // Incomplete (no final chunk)
            
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isValidMethod(String method) {
        if (method.isEmpty()) return false;
        for (char c : method.toCharArray()) {
            if (!isTokenChar(c) || Character.isLowerCase(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidHeaderName(String name) {
        if (name.isEmpty()) return false;
        for (char c : name.toCharArray()) {
            if (!isTokenChar(c)) return false;
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

    // Result classes
    
    public static class ParseResult {
        public HTTPRequest request;
        public boolean complete;
        public int bytesConsumed;
    }
    
    static class ChunkResult {
        byte[] body;
        int bytesConsumed;
        
        ChunkResult(byte[] body, int bytesConsumed) {
            this.body = body;
            this.bytesConsumed = bytesConsumed;
        }
    }
}