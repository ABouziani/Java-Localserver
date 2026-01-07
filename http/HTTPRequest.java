package http;

import java.util.HashMap;
import java.util.Map;

public class HTTPRequest {

    public String method;
    public String path;
    public String version;
    public Map<String, String> headers = new HashMap<>();
    public String body;
    public boolean valid = true;
    public String remain = "";
    public String contendType = "";
    
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
        System.out.println(r.errorCode);
        System.out.println(statusCode);
        return r;
    }
    
    public String getErrorResponse() {
        if (valid) {
            return null;
        }
        String statusText = getStatusText(this.errorCode);
        String body = "<html><body><h1>" + errorCode + " " + statusText + "</h1><p>" + errorMessage + "</p></body></html>";
        
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(errorCode).append(" ").append(statusText).append("\r\n");
        response.append("Content-Type: text/html; charset=utf-8\r\n");
        response.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");
        response.append(body);
        return response.toString();
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