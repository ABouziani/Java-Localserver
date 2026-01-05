package http;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class HTTPParser {

    public static long CLIENT_MAX_BODY = 104857600; // 10MB
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final int MAX_HEADERS_SIZE = 16384;
    private static final int MAX_URI_LENGTH = 8192;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOUBLE_CRLF = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Parses HTTP request from ByteBuffer.
     * Returns HTTPRequest object or null if incomplete.
     * Advances buffer position on successful parse.
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
            
            // Extract headers as bytes then convert to string
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
            
            if (!method.equals("GET") && 
                !method.equals("POST") && 
                !method.equals("DELETE")) {
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

            // Validate Host header for HTTP/1.1
            if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
                return HTTPRequest.invalid(400, "Missing Host header (required for HTTP/1.1)");
            }

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
                
            // Parse body (keep as bytes)
            byte[] body = null;
            int bodyStartPos = buffer.position();
            
            if (method.equals("POST") || method.equals("PUT")) {
                if (!isChunked && contentLength == -1) {
                    return HTTPRequest.invalid(411, "POST requires Content-Length or Transfer-Encoding: chunked");
                }
                
                if (isChunked) {
                    ChunkResult result = parseChunkedBodyBytes(buffer);
                    if (result == null) {
                        buffer.reset();
                        return null; // Incomplete
                    }
                    
                    body = result.body;
                    
                    if (body.length > CLIENT_MAX_BODY) {
                        return HTTPRequest.invalid(413, "Request body too large");
                    }
                    
                } else {
                    if (contentLength > CLIENT_MAX_BODY) {
                        return HTTPRequest.invalid(413, "Content-Length exceeds maximum allowed size");
                    }
                    
                    if (buffer.remaining() < contentLength) {
                        buffer.reset();
                        return null; // Incomplete body
                    }
                    
                    body = new byte[(int) contentLength];
                    buffer.get(body);
                }
                
            } else {
                // GET, DELETE - no body expected
                if (isChunked || contentLength > 0) {
                    return HTTPRequest.invalid(400, method + " request must not have a body");
                }
            }

            // Create successful request
            HTTPRequest request = new HTTPRequest();
            request.method = method;
            
            try {
                request.path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                request.path = rawPath;
            }
            
            request.version = version;
            request.headers = headers;
            try {
            request.tempFilePath = Files.createTempFile("upload-", ".tmp");
            request.tempFileChannel = FileChannel.open(
                request.tempFilePath,
                StandardOpenOption.WRITE);
                ByteBuffer bb = ByteBuffer.wrap(body);
                request.tempFileChannel.write(bb);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            // request.bodyBytes = body;
            request.valid = true;
            
            return request;
            
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
     * Returns ChunkResult with body bytes, or null if incomplete.
     * Advances buffer position on success.
     */
    private static ChunkResult parseChunkedBodyBytes(ByteBuffer buffer) {
        buffer.mark();
        ByteBuffer bodyAccumulator = ByteBuffer.allocate((int) CLIENT_MAX_BODY);
        
        try {
            while (buffer.hasRemaining()) {
                // Find chunk size line (\r\n)
                int lineStart = buffer.position();
                int lineEnd = findPattern(buffer, CRLF);
                
                if (lineEnd == -1) {
                    buffer.reset();
                    return null; // Incomplete
                }
                
                // Read chunk size line
                byte[] sizeLineBytes = new byte[lineEnd - lineStart];
                buffer.get(sizeLineBytes);
                String chunkSizeLine = new String(sizeLineBytes, StandardCharsets.UTF_8).trim();
                
                // Skip \r\n
                buffer.position(buffer.position() + 2);
                
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
                    buffer.reset();
                    return null; // Invalid chunk size
                }
                
                if (chunkSize < 0) {
                    buffer.reset();
                    return null;
                }
                
                // Last chunk
                if (chunkSize == 0) {
                    // Skip trailers if any (look for final \r\n\r\n)
                    int trailersEnd = findPattern(buffer, CRLF);
                    if (trailersEnd == -1) {
                        buffer.reset();
                        return null; // Incomplete
                    }
                    
                    // Check if empty line (just \r\n) or trailers
                    if (trailersEnd == buffer.position()) {
                        // Empty line, we're done
                        buffer.position(buffer.position() + 2);
                    } else {
                        // Has trailers, find \r\n\r\n
                        int finalEnd = findPattern(buffer, DOUBLE_CRLF);
                        if (finalEnd == -1) {
                            buffer.reset();
                            return null; // Incomplete
                        }
                        buffer.position(finalEnd + 4);
                    }
                    
                    byte[] finalBody = new byte[bodyAccumulator.position()];
                    bodyAccumulator.flip();
                    bodyAccumulator.get(finalBody);
                    
                    return new ChunkResult(finalBody);
                }
                
                // Read chunk data
                if (buffer.remaining() < chunkSize + 2) {
                    buffer.reset();
                    return null; // Incomplete chunk
                }
                
                byte[] chunkData = new byte[chunkSize];
                buffer.get(chunkData);
                bodyAccumulator.put(chunkData);
                
                // Skip trailing \r\n
                buffer.position(buffer.position() + 2);
            }
            
            buffer.reset();
            return null; // Incomplete (no final chunk)
            
        } catch (Exception e) {
            buffer.reset();
            return null; // Parse error
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