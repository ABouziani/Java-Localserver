package cgi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;

public class CGIHandler {

    private final ServerConfig cfg;

    public CGIHandler(ServerConfig cfg) {
        this.cfg = cfg;
    }

    public HTTPResponse execute(HTTPRequest req, File file) throws IOException {
        String ext = file.getName().contains(".")
                ? file.getName().substring(file.getName().lastIndexOf('.') + 1)
                : "";

        String interpreter = "python3"; // default; can be configurable

        ProcessBuilder pb = new ProcessBuilder(interpreter, file.getAbsolutePath());
        Map<String, String> env = pb.environment();

        env.put("REQUEST_METHOD", req.method);
        env.put("PATH_INFO", file.getAbsolutePath());
        env.put("SCRIPT_NAME", file.getName());
        env.put("SCRIPT_FILENAME", file.getAbsolutePath());
        env.put("SERVER_PROTOCOL", req.version);

        req.headers.forEach((k, v) -> env.put("HTTP_" + k.toUpperCase().replace('-', '_'), v));

        if (req.headers.containsKey("content-length")) {
            env.put("CONTENT_LENGTH", req.headers.get("content-length"));
        }
        if (req.headers.containsKey("content-type")) {
            env.put("CONTENT_TYPE", req.headers.get("content-type"));
        }

        Process p = pb.start();

        if (req.body != null && req.body.length > 0) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(req.body);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            is.transferTo(baos);
        }

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (InputStream es = p.getErrorStream()) {
            es.transferTo(errors);
        }

        try {
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return HTTPResponse.simple(500, "CGI script timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HTTPResponse.simple(500, "CGI script interrupted");
        }

        byte[] output = baos.toByteArray();

      
        int sep = findHeaderBodySeparator(output);

        HTTPResponse r = new HTTPResponse();

        if (sep != -1) {
            // CGI returned headers + body
            byte[] headerBytes = new byte[sep];
            System.arraycopy(output, 0, headerBytes, 0, sep);

            byte[] bodyBytes = new byte[output.length - sep - 4];
            System.arraycopy(output, sep + 4, bodyBytes, 0, bodyBytes.length);

            String headers = new String(headerBytes, StandardCharsets.UTF_8);
            for (String line : headers.split("\r\n")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String name = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();

                    if (name.equalsIgnoreCase("Status")) {
                        try {
                            String[] parts = value.split(" ", 2);
                            r.status = Integer.parseInt(parts[0]);
                        } catch (Exception e) {
                            r.status = 200;
                        }
                    } else {
                        r.headers.put(name, value);
                    }
                }
            }

            r.body = bodyBytes;

            if (r.status == 0) {
                r.status = 200;
            }

            r.headers.putIfAbsent("Content-Length", String.valueOf(r.body.length));

        } else {
            r.body = output;
            r.status = 200;
            r.headers.put("Content-Type", "text/html; charset=utf-8");
            r.headers.put("Content-Length", String.valueOf(r.body.length));
        }

        return r;
    }

    
    private int findHeaderBodySeparator(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' &&
                    data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}