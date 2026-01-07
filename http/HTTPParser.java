package http;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import client.ClientState;

public class HTTPParser {

    public static long CLIENT_MAX_BODY = 10485760; // 10MB
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final int MAX_HEADERS_SIZE = 16384;
    private static final int MAX_URI_LENGTH = 8192;

    public static HTTPRequest ParseHeaders(String raw) {
        
        int headersEnd = raw.indexOf("\r\n\r\n");
        if (headersEnd == -1) {
            return null; 
        }

        String headersSection = raw.substring(0, headersEnd);
        
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

        
        if (!isValidMethod(method)) {
            return HTTPRequest.invalid(400, "Invalid method format");
        }
        
        if (!method.equals("GET") && 
            !method.equals("POST") && 
            !method.equals("DELETE") 
        ){
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
        
        if (!version.equals("HTTP/1.1") ) {
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

        
        if (version.equals("HTTP/1.1")) {
            if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
                return HTTPRequest.invalid(400, "Missing Host header (required for HTTP/1.1)");
            }
        }

        
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
                if (values.length != 0){
                    String firstValue = values[0].trim();
                    
                    for (String v : values) {
                        if (!v.trim().equals(firstValue)) {
                            return HTTPRequest.invalid(400, "Multiple different Content-Length values");
                        }
                    }
                    clValue = firstValue;
                }
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

        HTTPRequest request = new HTTPRequest();
        request.contendType = headers.getOrDefault("content-type", "");
        request.headers = headers;
        request.version = version;
        request.method = method;
        request.path = rawPath;
        request.valid = true;
        return request;
        // String body = null;
        // String remaining = "";
        // int bodyStart = headersEnd + 4;
        // String bodyData = raw.substring(bodyStart);
        
        // if (method.equals("POST") || method.equals("PUT")) {
            
        //     if (!isChunked && contentLength == -1) {
        //         return HTTPRequest.invalid(411, "POST requires Content-Length or Transfer-Encoding: chunked");
        //     }
            
        //     if (isChunked) {
        //         ChunkResult result = parseChunkedBody(bodyData);
        //         if (result == null) {
        //             return null; 
        //         }
                
        //         body = result.body;
        //         remaining = result.remaining;
                
        //         if (body.length() > CLIENT_MAX_BODY) {
        //             return HTTPRequest.invalid(413, "Request body too large");
        //         }
                
        //     } else {
        //         if (contentLength > CLIENT_MAX_BODY) {
        //             return HTTPRequest.invalid(413, "Content-Length exceeds maximum allowed size");
        //         }
                
        //         if (bodyData.length() < contentLength) {
        //             return null; 
        //         }
                
        //         body = bodyData.substring(0, (int) contentLength);
        //         remaining = bodyData.substring((int) contentLength);
        //     }
            
        // } else {
        //     body = null;
        //     remaining = bodyData;
            
        //     if (isChunked || contentLength > 0) {
        //         return HTTPRequest.invalid(400, method + " request must not have a body");
        //     }
        // }

        
        
        // request.method = method;
        
        // try {
        //     request.path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        // } catch (Exception e) {
        //     request.path = rawPath;
        // }
        
        // // request.version = version;
        // // request.headers = headers;
        // request.body = body;
        // request.valid = true;
        // request.remain = remaining;
        
        // return request;
    }

    public static HTTPRequest complateRequest(ClientState state) throws IOException{
        // String body = null;
        // String remaining = "";
        // int bodyStart = state.indexOfHeadersEnd;
        String method = state.request.method;
        Boolean isChunked = state.chunked;
        Long contentLength = state.expectedLength;
        

        if (method.equals("POST")) {
            
            if (!isChunked && contentLength == -1) {
                return HTTPRequest.invalid(411, "POST requires Content-Length or Transfer-Encoding: chunked");
            }
            
            if (isChunked) {
                state.handleChunkedBody();
                if (state.receivedLength > CLIENT_MAX_BODY) {
                    return HTTPRequest.invalid(413, "Request body too large");
                }
                if (state.currentChunkSize == -1) {
                    return null; 
                }
                
                // body = result.body;
                // remaining = result.remaining;
                
                
            } else {
                if (contentLength > CLIENT_MAX_BODY) {
                    return HTTPRequest.invalid(413, "Content-Length exceeds maximum allowed size");
                }
                state.handleContentLengthBody();
                if (state.receivedLength < contentLength) {
                    return null; 
                }
                
                // body = bodyData.substring(0, (int) contentLength);
                // remaining = bodyData.substring((int) contentLength);
            }
            
        } else {
            // body = null;
            // remaining = bodyData;
            
            if (isChunked || contentLength > 0) {
                return HTTPRequest.invalid(400, method + " request must not have a body");
            }
        }

        
        
        // request.method = method;
        
        // try {
        //     state.request.path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        // } catch (Exception e) {
        //     request.path = rawPath;
        // }
        
        // request.version = version;
        // request.headers = headers;
        // request.body = body;
        state.request.valid = true;
        // request.remain = remaining;
        
        return state.request;
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

    
    private static ChunkResult parseChunkedBody(String data) {
        StringBuilder fullBody = new StringBuilder();
        int position = 0;
        
        try {
            while (position < data.length()) {
                
                int lineEnd = data.indexOf("\r\n", position);
                if (lineEnd == -1) {
                    return null; 
                }
                
                String chunkSizeLine = data.substring(position, lineEnd).trim();
                
                int semicolonPos = chunkSizeLine.indexOf(';');
                if (semicolonPos != -1) {
                    chunkSizeLine = chunkSizeLine.substring(0, semicolonPos).trim();
                }
                
                if (chunkSizeLine.isEmpty()) {
                    position = lineEnd + 2;
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
                
                if (chunkSize == 0) {
                    position = lineEnd + 2;
                    
                    int nextCrlf = data.indexOf("\r\n", position);
                    if (nextCrlf == -1) {
                        return null;
                    }
                    
                    String afterZero = data.substring(position, nextCrlf).trim();
                    
                    if (afterZero.isEmpty()) {
                        String remaining = data.substring(nextCrlf + 2);
                        return new ChunkResult(fullBody.toString(), remaining);
                    } else {
                        int trailersEnd = data.indexOf("\r\n\r\n", position);
                        if (trailersEnd == -1) {
                            return null; // Incomplete
                        }
                        String remaining = data.substring(trailersEnd + 4);
                        return new ChunkResult(fullBody.toString(), remaining);
                    }
                }
                
                position = lineEnd + 2;
                
                if (position + chunkSize + 2 > data.length()) {
                    return null; // Incomplete chunk
                }
                
                String chunkData = data.substring(position, position + chunkSize);
                fullBody.append(chunkData);
                
                position += chunkSize + 2;
            }
            
            return null; // Incomplete (no final chunk)
            
        } catch (Exception e) {
            return null; // Parse error
        }
    }

    static class ChunkResult {
        String body;
        String remaining;
        
        ChunkResult(String body, String remaining) {
            this.body = body;
            this.remaining = remaining;
        }
    }
}