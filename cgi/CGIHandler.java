package cgi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

        // 1️⃣ Build process
        String interpreter = "python3"; // or absolute path if needed
        ProcessBuilder pb = new ProcessBuilder(interpreter, file.getAbsolutePath());

        // 2️⃣ Environment variables (CGI-like)
        Map<String, String> env = pb.environment();
        env.put("REQUEST_METHOD", req.method);
        env.put("PATH_INFO", file.getAbsolutePath());

        req.headers.forEach((k, v) -> env.put("HTTP_" + k.toUpperCase().replace('-', '_'), v));

        // 3️⃣ Start process
        Process p = pb.start();

        // 4️⃣ Write request body → stdin (IMPORTANT: close = EOF)
        try (OutputStream os = p.getOutputStream()) {
            if (req.tempFilePath != null && Files.exists(req.tempFilePath)) {
                byte[] fileBuf = Files.readAllBytes(req.tempFilePath);
                System.out.println(new String(fileBuf));
                os.write(fileBuf);
                os.flush();
            }

        }

        // 5️⃣ Read stdout & stderr
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        p.getInputStream().transferTo(stdout);
        p.getErrorStream().transferTo(stderr);

        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        // Debug stderr if exists
        if (stderr.size() > 0) {
            System.err.println("CGI STDERR:\n" +
                    stderr.toString(StandardCharsets.UTF_8));
        }

        // 6️⃣ Parse output
        String out = stdout.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        HTTPResponse r = new HTTPResponse();

        int sep = out.indexOf("\n\n");

        if (sep != -1) {
            // Headers + body
            String headersPart = out.substring(0, sep);
            String bodyPart = out.substring(sep + 2);

            for (String line : headersPart.split("\n")) {
                int c = line.indexOf(':');
                if (c > 0) {
                    r.headers.put(
                            line.substring(0, c).trim(),
                            line.substring(c + 1).trim());
                }
            }

            r.body = bodyPart;
        } else {
            // Body only
            r.body = out;
        }

        r.status = 200;
        r.headers.putIfAbsent(
                "Content-Length",
                String.valueOf(r.body.getBytes(StandardCharsets.UTF_8).length));

        return r;
    }

}
