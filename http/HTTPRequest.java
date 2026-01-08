package http;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class HTTPRequest {

    // Temp file reference — assigned *after* streaming completes (e.g., in VirtualServer)
    public FileChannel tempFileChannel;
    public Path tempFilePath;

    // Parsed metadata (set during header parsing)
    public String method;
    public String path;
    public String version;
    public Map<String, String> headers = new HashMap<>();

    // Streaming decision flags — set by HTTPParser
    public boolean needsStreaming = false;
    public long expectedContentLength = -1;  // -1 means unknown or no body
    public boolean isMultipart = false;
    public boolean isChunked = false;

    // Legacy (deprecated)
    @Deprecated
    public String body;
    @Deprecated
    public String remain = "";

    // Validation
    public boolean valid = true;
    public int errorCode = 0;
    public String errorMessage = "";

    // Factory for invalid requests
    static HTTPRequest invalid() {
        return invalid(400, "Bad Request");
    }

    static HTTPRequest invalid(int statusCode, String message) {
        HTTPRequest r = new HTTPRequest();
        r.valid = false;
        r.errorCode = statusCode;
        r.errorMessage = message;
        return r;
    }

    // Error response helper
    public String getErrorResponse() {
        if (valid) return null;
        String statusText = getStatusText(this.errorCode);
        String bodyHtml = "<html><body><h1>" + errorCode + " " + statusText + "</h1><p>" + errorMessage + "</p></body></html>";
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(errorCode).append(" ").append(statusText).append("\r\n");
        response.append("Content-Type: text/html; charset=utf-8\r\n");
        response.append("Content-Length: ").append(bodyHtml.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");
        response.append(bodyHtml);
        return response.toString();
    }

    private static String getStatusText(int code) {
        switch (code) {
            case 400: return "Bad Request";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 431: return "Request Header Fields Too Large";
            case 501: return "Not Implemented";
            case 505: return "HTTP Version Not Supported";
            default: return "Error";
        }
    }
}