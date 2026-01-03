package http;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import configurations.ServerConfig;

public class HTTPResponse {
    public int status;
    public Map<String, String> headers = new HashMap<>();

    public byte[] body = null;

    public boolean streamed = false;
    public File file = null;
    public boolean sentHeader = false;
    public boolean keepAlive = true;
    public FileChannel fileChannel = null;
    public long filePos = 0;

    public static HTTPResponse error(int code, ServerConfig cfg) {
        HTTPResponse r = new HTTPResponse();
        r.status = code;
        r.headers.put("Connection", "close");
        r.keepAlive = false;
        String path = cfg.getErrorPages().getOrDefault(String.valueOf(code), null);
        if (path != null && Files.exists(Paths.get(path))) {
            r.streamed = true;
            r.file = new File(path);
            r.headers.put("Content-Length", String.valueOf(r.file.length()));
            r.headers.put("Content-Type", "text/html");
        } else {
            String bodyText = "<html><body><h1>" + code + "</h1></body></html>";
            r.body = bodyText.getBytes(StandardCharsets.UTF_8);
            r.headers.put("Content-Length", String.valueOf(r.body.length));
            r.headers.put("Content-Type", "text/html");
        }
        return r;
    }

    public static HTTPResponse redirect(String to) {
        HTTPResponse r = new HTTPResponse();
        r.status = 302;
        r.headers.put("Location", to);
        r.body = new byte[0];
        r.headers.put("Content-Length", "0");
        return r;
    }

    public static HTTPResponse serveFile(File f, boolean keepAlive) {
        HTTPResponse r = new HTTPResponse();
        r.status = 200;
        r.streamed = true;
        r.file = f;
        r.keepAlive = keepAlive;
        r.headers.put("Content-Length", String.valueOf(f.length()));
        r.headers.put("Content-Type", guessMime(f.getName()));
        return r;
    }

    public static HTTPResponse dirListing(File f) {
        StringBuilder b = new StringBuilder();
        b.append("<html><body><ul>");
        for (var x : f.listFiles()) {
            b.append("<li><a href='").append(x.getName()).append("'>").append(x.getName()).append("</a></li>");
        }
        b.append("</ul></body></html>");

        HTTPResponse r = new HTTPResponse();
        r.body = b.toString().getBytes(StandardCharsets.UTF_8);
        r.status = 200;
        r.headers.put("Content-Length", String.valueOf(r.body.length));
        r.headers.put("Content-Type", "text/html");
        return r;
    }

    public static HTTPResponse simple(int code, String text) {
        HTTPResponse r = new HTTPResponse();
        r.status = code;
        r.body = text.getBytes(StandardCharsets.UTF_8);
        r.headers.put("Content-Length", String.valueOf(r.body.length));
        r.headers.put("Content-Type", "text/plain");
        return r;
    }

    
    public void setBody(String text) {
        if (text == null) {
            this.body = null;
        } else {
            this.body = text.getBytes(StandardCharsets.UTF_8);
        }
    }

    
    public String getBodyAsString() {
        if (body == null) {
            return null;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public byte[] toHeaderBytes() {
        StringBuilder b = new StringBuilder();
        b.append("HTTP/1.1 ").append(status).append(" ").append(statusText(status)).append("\r\n");
        headers.putIfAbsent("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT"))));
        if (keepAlive)
            headers.putIfAbsent("Connection", "keep-alive");
        else
            headers.putIfAbsent("Connection", "close");
        for (var e : headers.entrySet())
            b.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        b.append("\r\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String statusText(int s) {
        switch (s) {
            case 200:
                return "OK";
            case 302:
                return "Found";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 411:
                return "Length Required";
            case 413:
                return "Content Too Large";
            case 414:
                return "URI Too Long";
            case 431:
                return "Request Header Fields Too Large";
            case 500:
                return "Internal Server Error";
            case 501:
                return "Not Implemented";
            case 505:
                return "HTTP Version Not Supported";
            default:
                return "";
        }
    }

    static String guessMime(String name) {
        if (name.endsWith(".html") || name.endsWith(".htm"))
            return "text/html";
        if (name.endsWith(".css"))
            return "text/css";
        if (name.endsWith(".js"))
            return "application/javascript";
        if (name.endsWith(".png"))
            return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
            return "image/jpeg";
        if (name.endsWith(".gif"))
            return "image/gif";
        if (name.endsWith(".svg"))
            return "image/svg+xml";
        if (name.endsWith(".json"))
            return "application/json";
        if (name.endsWith(".xml"))
            return "application/xml";
        if (name.endsWith(".pdf"))
            return "application/pdf";
        return "application/octet-stream";
    }
}