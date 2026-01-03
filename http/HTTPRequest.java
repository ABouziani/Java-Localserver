package http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HTTPRequest {

    public String method;
    public String path;
    public String version;
    public Map<String, String> headers = new HashMap<>();
    
    public byte[] body;
    
    public boolean valid = true;
    
    public byte[] remain = new byte[0];
    
    public int errorCode = 0;
    public String errorMessage = "";

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
    
    
    public String getBodyAsString() {
        if (body == null) {
            return null;
        }
        return new String(body, StandardCharsets.UTF_8);
    }
    

    public String getBodyAsString(String charset) {
        if (body == null) {
            return null;
        }
        try {
            return new String(body, charset);
        } catch (Exception e) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public boolean hasBody() {
        return body != null && body.length > 0;
    }
    
    
    public int getBodyLength() {
        return body == null ? 0 : body.length;
    }
    

    public byte[] getRemainingBytes() {
        return remain;
    }
   
    public boolean hasRemaining() {
        return remain != null && remain.length > 0;
    }
    
    public String getErrorResponse() {
        if (valid) {
            return null;
        }
        String statusText = getStatusText(this.errorCode);
        String bodyText = "<html><body><h1>" + errorCode + " " + statusText + "</h1><p>" + errorMessage + "</p></body></html>";
        
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(errorCode).append(" ").append(statusText).append("\r\n");
        response.append("Content-Type: text/html; charset=utf-8\r\n");
        response.append("Content-Length: ").append(bodyText.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");
        response.append(bodyText);
        return response.toString();
    }
    
   
    public byte[] getErrorResponseBytes() {
        String response = getErrorResponse();
        if (response == null) {
            return null;
        }
        return response.getBytes(StandardCharsets.UTF_8);
    }
    
    private static String getStatusText(int code) {
        switch (code) {
            case 400: return "Bad Request";
            case 411: return "Length Required";
            case 413: return "Content Too Large";
            case 414: return "URI Too Long";
            case 431: return "Request Header Fields Too Large";
            case 501: return "Not Implemented";
            case 505: return "HTTP Version Not Supported";
            default: return "Error";
        }
    }
}