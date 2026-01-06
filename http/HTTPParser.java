package http;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HTTPParser {

    public static long CLIENT_MAX_BODY = 10485760; // 10MB
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final int MAX_HEADERS_SIZE = 16384;
    private static final int MAX_URI_LENGTH = 8192;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOUBLE_CRLF = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    public static HTTPRequest parseHeaders(ByteBuffer buffer) {
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
            
            // Extract headers as bytes
            byte[] headersBytes = new byte[headersLength];
            buffer.position(startPos);
            buffer.get(headersBytes);
            String headersSection = new String(headersBytes, StandardCharsets.UTF_8);
            
            // Skip \r\n\r\n
            buffer.position(headersEnd + 4);
            
            // Parse headers section
            String[] lines = headersSection.split("\r\n");
            if (lines.length == 0) {
                return HTTPRequest.invalid(400, "Empty request");
            }
            
            // Parse request line
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
            
            if (rawPath.contains(" ") || rawPath.contains("\t")) {
                return HTTPRequest.invalid(400, "Request target contains unencoded whitespace");
            }
            
            if (rawPath.contains("\r") || rawPath.contains("\n") || rawPath.contains("\0")) {
                return HTTPRequest.invalid(400, "Request target contains control characters");
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
                String line = lines[i];
                
                if (line.length() > MAX_HEADER_LINE_LENGTH) {
                    return HTTPRequest.invalid(431, "Header line too long");
                }
                
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    return HTTPRequest.invalid(400, "Obsolete line folding detected");
                }
                
                line = line.trim();
                if (line.isEmpty()) continue;
                
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
                
                if (headers.containsKey(normalizedName)) {
                    headers.put(normalizedName, headers.get(normalizedName) + ", " + value);
                } else {
                    headers.put(normalizedName, value);
                }
            }

            // Validate Host header
            if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
                return HTTPRequest.invalid(400, "Missing Host header (required for HTTP/1.1)");
            }

            // Create request with headers only (no body yet)
            HTTPRequest request = new HTTPRequest();
            request.method = method;
            
            try {
                request.path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                request.path = rawPath;
            }
            
            request.version = version;
            request.headers = headers;
            request.bodyBytes = null; // Body not parsed yet
            request.valid = true;
            
            return request;
            
        } catch (Exception e) {
            buffer.reset();
            return HTTPRequest.invalid(400, "Parse error: " + e.getMessage());
        }
    }

    /**
     * Parse body for a request that already has headers parsed (step 2).
     * Updates the request object with body data.
     * Returns true if body complete, false if incomplete, null if error.
     */
    public static Boolean parseBody(HTTPRequest request, ByteBuffer buffer) {
        if (request == null || !request.valid) {
            return null; // Error
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
                    request.valid = false;
                    request.errorCode = 400;
                    request.errorMessage = "Both Content-Length and chunked Transfer-Encoding present";
                    return null; // Error
                }
                
                String clValue = headers.get("content-length");
                
                if (clValue.contains(",")) {
                    String[] values = clValue.split(",");
                    String firstValue = values[0].trim();
                    
                    for (String v : values) {
                        if (!v.trim().equals(firstValue)) {
                            request.valid = false;
                            request.errorCode = 400;
                            request.errorMessage = "Multiple different Content-Length values";
                            return null; // Error
                        }
                    }
                    clValue = firstValue;
                }
                
                try {
                    contentLength = Long.parseLong(clValue.trim());
                    if (contentLength < 0) {
                        request.valid = false;
                        request.errorCode = 400;
                        request.errorMessage = "Negative Content-Length";
                        return null; // Error
                    }
                } catch (NumberFormatException e) {
                    request.valid = false;
                    request.errorCode = 400;
                    request.errorMessage = "Invalid Content-Length value";
                    return null; // Error
                }
            }

            // Parse body based on method
            byte[] body = null;
            
            if (method.equals("POST")) {
                if (!isChunked && contentLength == -1) {
                    request.valid = false;
                    request.errorCode = 411;
                    request.errorMessage = "POST requires Content-Length or Transfer-Encoding: chunked";
                    return null; // Error
                }
                
                if (isChunked) {
                    ChunkResult result = parseChunkedBodyBytes(buffer);
                    if (result == null) {
                        return false; // Incomplete
                    }
                    
                    body = result.body;
                    
                    if (body.length > CLIENT_MAX_BODY) {
                        request.valid = false;
                        request.errorCode = 413;
                        request.errorMessage = "Request body too large";
                        return null; // Error
                    }
                    
                } else {
                    if (contentLength > CLIENT_MAX_BODY) {
                        request.valid = false;
                        request.errorCode = 413;
                        request.errorMessage = "Content-Length exceeds maximum allowed size";
                        return null; // Error
                    }
                    
                    if (buffer.remaining() < contentLength) {
                        return false; // Incomplete body
                    }
                    
                    body = new byte[(int) contentLength];
                    buffer.get(body);
                }
                
            } else {
                // GET, DELETE - no body expected
                if (isChunked || contentLength > 0) {
                    request.valid = false;
                    request.errorCode = 400;
                    request.errorMessage = method + " request must not have a body";
                    return null; // Error
                }
            }

            // Body parsed successfully
            request.bodyBytes = body;
            return true; // Complete
            
        } catch (Exception e) {
            request.valid = false;
            request.errorCode = 400;
            request.errorMessage = "Body parse error: " + e.getMessage();
            return null; // Error
        }
    }

    /**
     * Legacy method: Parse complete request (headers + body) in one call.
     * Kept for backward compatibility.
     */
    public static HTTPRequest tryParse(ByteBuffer buffer) {
        buffer.mark();
        
        // Step 1: Parse headers
        HTTPRequest request = parseHeaders(buffer);
        if (request == null) {
            buffer.reset();
            return null; // Headers incomplete
        }
        
        if (!request.valid) {
            return request; // Header parsing error
        }
        
        // Step 2: Parse body
        Boolean bodyResult = parseBody(request, buffer);
        if (bodyResult == null) {
            return request; // Body parsing error (request.valid = false)
        }
        
        if (!bodyResult) {
            buffer.reset();
            return null; // Body incomplete
        }
        
        return request; // Complete
    }

    /**
     * Finds pattern in buffer starting from current position.
     */
    private static int findPattern(ByteBuffer buffer, byte[] pattern) {
        int startPos = buffer.position();
        int limit = buffer.limit();
        
        outer: for (int i = startPos; i <= limit - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buffer.get(i + j) != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Parses chunked body from ByteBuffer.
     */
    private static ChunkResult parseChunkedBodyBytes(ByteBuffer buffer) {
        buffer.mark();
        ByteBuffer bodyAccumulator = ByteBuffer.allocate((int) CLIENT_MAX_BODY);
        
        try {
            while (buffer.hasRemaining()) {
                int lineStart = buffer.position();
                int lineEnd = findPattern(buffer, CRLF);
                
                if (lineEnd == -1) {
                    buffer.reset();
                    return null;
                }
                
                byte[] sizeLineBytes = new byte[lineEnd - lineStart];
                buffer.get(sizeLineBytes);
                String chunkSizeLine = new String(sizeLineBytes, StandardCharsets.UTF_8).trim();
                
                buffer.position(buffer.position() + 2);
                
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
                    buffer.reset();
                    return null;
                }
                
                if (chunkSize < 0) {
                    buffer.reset();
                    return null;
                }
                
                if (chunkSize == 0) {
                    int trailersEnd = findPattern(buffer, CRLF);
                    if (trailersEnd == -1) {
                        buffer.reset();
                        return null;
                    }
                    
                    if (trailersEnd == buffer.position()) {
                        buffer.position(buffer.position() + 2);
                    } else {
                        int finalEnd = findPattern(buffer, DOUBLE_CRLF);
                        if (finalEnd == -1) {
                            buffer.reset();
                            return null;
                        }
                        buffer.position(finalEnd + 4);
                    }
                    
                    byte[] finalBody = new byte[bodyAccumulator.position()];
                    bodyAccumulator.flip();
                    bodyAccumulator.get(finalBody);
                    
                    return new ChunkResult(finalBody);
                }
                
                if (buffer.remaining() < chunkSize + 2) {
                    buffer.reset();
                    return null;
                }
                
                byte[] chunkData = new byte[chunkSize];
                buffer.get(chunkData);
                bodyAccumulator.put(chunkData);
                
                buffer.position(buffer.position() + 2);
            }
            
            buffer.reset();
            return null;
            
        } catch (Exception e) {
            buffer.reset();
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

    static class ChunkResult {
        byte[] body;
        
        ChunkResult(byte[] body) {
            this.body = body;
        }
    }
}