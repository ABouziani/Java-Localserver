package http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HTTPParser {

    public static long CLIENT_MAX_BODY = 10485760;

    public static HTTPRequest tryParse(String raw) {
        
        int headersEnd = raw.indexOf("\r\n\r\n");
        if (headersEnd == -1) {
            return null;
        }

        String headersSection = raw.substring(0, headersEnd);
        String[] lines = headersSection.split("\r\n");
        
        if (lines.length == 0) {
            return HTTPRequest.invalid();
        }
        
        String requestLine = lines[0];
        String[] parts = requestLine.split(" ");
        
        if (parts.length != 3) {
            return HTTPRequest.invalid();
        }

        String method = parts[0].toUpperCase();
        String rawPath = parts[1];
        String version = parts[2];

        if (!version.equals("HTTP/1.1")) {
            return HTTPRequest.invalid();
        }

        if (!method.equals("GET") && 
            !method.equals("POST") && 
            !method.equals("DELETE")) {
            return HTTPRequest.invalid();
        }

        Map<String, String> headers = new HashMap<>();
        
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            int colonIdx = line.indexOf(':');
            if (colonIdx == -1) {
                return HTTPRequest.invalid();
            }
            
            String name = line.substring(0, colonIdx).trim().toLowerCase();
            String value = line.substring(colonIdx + 1).trim();
            
            if (headers.containsKey(name)) {
                headers.put(name, headers.get(name) + ", " + value);
            } else {
                headers.put(name, value);
            }
        }

        if (!headers.containsKey("host") || headers.get("host").trim().isEmpty()) {
            return HTTPRequest.invalid();
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
                return HTTPRequest.invalid();
            }
            
            try {
                contentLength = Long.parseLong(headers.get("content-length"));
                if (contentLength < 0) {
                    return HTTPRequest.invalid();
                }
            } catch (NumberFormatException e) {
                return HTTPRequest.invalid();
            }
        }

        String body = null;
        String remaining = "";
        int bodyStart = headersEnd + 4;
        String bodyData = raw.substring(bodyStart);
        
        if (method.equals("POST")) {
            if (!isChunked && contentLength == -1) {
                return HTTPRequest.invalid();
            }
            
            if (isChunked) {
                ChunkResult result = parseChunkedBody(bodyData);
                if (result == null) {
                    return null;
                }
                
                body = result.body;
                remaining = result.remaining;
                
                if (body.length() > CLIENT_MAX_BODY) {
                    return HTTPRequest.invalid();
                }
                
            } else {
                if (contentLength > CLIENT_MAX_BODY) {
                    return HTTPRequest.invalid();
                }
                
                if (bodyData.length() < contentLength) {
                    return null;
                }
                
                body = bodyData.substring(0, (int) contentLength);
                remaining = bodyData.substring((int) contentLength);
            }
            
        } else {
            body = null;
            remaining = bodyData;
            
            if (isChunked || contentLength > 0) {
                return HTTPRequest.invalid();
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
        request.remaining = remaining;
        
        return request;
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
                            return null;
                        }
                        String remaining = data.substring(trailersEnd + 4);
                        return new ChunkResult(fullBody.toString(), remaining);
                    }
                }
                
                position = lineEnd + 2;
                
                if (position + chunkSize + 2 > data.length()) {
                    return null;
                }
                
                String chunkData = data.substring(position, position + chunkSize);
                fullBody.append(chunkData);
                
                position += chunkSize + 2;
            }
            
            return null;
            
        } catch (Exception e) {
            return null;
        }
    }

    private static class ChunkResult {
        String body;
        String remaining;
        
        ChunkResult(String body, String remaining) {
            this.body = body;
            this.remaining = remaining;
        }
    }
}
