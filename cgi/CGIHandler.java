package cgi;

public class CGIHandler {

    private final Config cfg;

    CGIHandler(Config cfg) {
        this.cfg = cfg;
    }

    HTTPResponse execute(HTTPRequest req, File file) throws IOException {
        // Only allowing .py as configured example
        String ext = file.getName().contains(".") ? file.getName().substring(file.getName().lastIndexOf('.') + 1) : "";
        String interpreter = "python3"; // default; can be configurable
        ProcessBuilder pb = new ProcessBuilder(interpreter, file.getAbsolutePath());
        Map<String, String> env = pb.environment();
        env.put("REQUEST_METHOD", req.method);
        env.put("PATH_INFO", file.getAbsolutePath());
        req.headers.forEach((k, v) -> env.put("HTTP_" + k.toUpperCase().replace('-', '_'), v));
        Process p = pb.start();
        if (req.body != null) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(req.body.getBytes(StandardCharsets.UTF_8));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            is.transferTo(baos);
        }
        try (InputStream es = p.getErrorStream()) {
            es.transferTo(new ByteArrayOutputStream());
        }
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        String out = baos.toString(StandardCharsets.UTF_8);
        // Expect CGI returns full HTTP headers + body or just body. We handle simply.
        int sep = out.indexOf("\r\n\r\n");
        HTTPResponse r = new HTTPResponse();
        if (sep != -1) {
            String hdrs = out.substring(0, sep);
            String body = out.substring(sep + 4);
            for (String line : hdrs.split("\r\n")) {
                int c = line.indexOf(':');
                if (c > 0) {
                    r.headers.put(line.substring(0, c), line.substring(c + 1).trim());
            
                }}
            r.body = body;
            r.status = 200;
            r.headers.putIfAbsent("Content-Length", String.valueOf(r.body.length()));
        } else {
            r.body = out;
            r.status = 200;
            r.headers.put("Content-Length", String.valueOf(r.body.length()));
        }
        return r;
    }
}
