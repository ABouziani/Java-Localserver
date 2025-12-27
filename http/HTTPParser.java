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

        if (!version.startsWith("HTTP/1.1")) {
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
            if (line.isEmpty())
                continue;

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

        String body = null;
        String remaining = "";
        int bodyStart = headersEnd + 4;
        String bodyData = raw.substring(bodyStart);

        if (method.equals("POST")) {
            if (!headers.containsKey("content-length")) {
                return HTTPRequest.invalid();
            }

            long contentLength;
            try {
                contentLength = Long.parseLong(headers.get("content-length"));
                if (contentLength < 0) {
                    return HTTPRequest.invalid();
                }
            } catch (NumberFormatException e) {
                return HTTPRequest.invalid();
            }

            if (contentLength > CLIENT_MAX_BODY) {
                return HTTPRequest.invalid();
            }

            if (bodyData.length() < contentLength) {
                return null;
            }

            body = bodyData.substring(0, (int) contentLength);
            remaining = bodyData.substring((int) contentLength);

        } else {
            body = null;
            remaining = bodyData;

            if (headers.containsKey("content-length")) {
                long cl;
                try {
                    cl = Long.parseLong(headers.get("content-length"));
                    if (cl > 0) {
                        return HTTPRequest.invalid();
                    }
                } catch (NumberFormatException e) {
                    return HTTPRequest.invalid();
                }
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
}
