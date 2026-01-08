package routing;

import cgi.CGIHandler;
import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import static java.nio.channels.FileChannel.open;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import session.Session;

;

public class Router {

    private final ServerConfig cfg;

    public Router(ServerConfig cfg) {
        this.cfg = cfg;
    }

    public HTTPResponse route(HTTPRequest req, Session sess, CGIHandler cgi) throws IOException {

        // basic validation
        if (!req.valid) {
            return HTTPResponse.error(400, cfg);
        }
        // find route by prefix (simple longest match)
        Route matched = null;
        String matchedPath = "";
        for (var en : cfg.getRoutes().entrySet()) {
            String p = en.getKey();
            if (req.path.startsWith(p) && p.length() >= matchedPath.length()) {
                matched = en.getValue();
                matchedPath = p;
            }
        }
        if (matched == null) {
            ////////////////////////////////////////////////////////
            matched = new Route();
            matched.root = cfg.getRoot();
        }
        // redirect
        if (matched.redirect != null) {
            return HTTPResponse.redirect(matched.redirect);
        }
        if (!matched.methods.isEmpty() && !matched.methods.contains(req.method)) {
            return HTTPResponse.error(405, cfg);
        }
        // map path
        Path fsroot = Paths.get(matched.root != null ? matched.root : cfg.getRoot());
        String rel = req.path.substring(matchedPath.length());
        if (rel.equals("") || rel.equals("/")) {
            rel = "/" + matched.index;
        }
        Path target = fsroot.resolve(rel.substring(1)).normalize();
        if (!target.startsWith(fsroot)) {
            return HTTPResponse.error(403, cfg);
        }
        File f = target.toFile();
        if (f.isDirectory()) {
            File idx = new File(f, matched.index);
            if (idx.exists()) {
                return HTTPResponse.serveFile(idx, true);
            }
            if (matched.dirList) {
                return HTTPResponse.dirListing(f);
            }
            return HTTPResponse.error(403, cfg);
        }
        // if (!f.exists())
        // return HTTPResponse.error(404, cfg);
        // CGI handling by extension
        String ext = getExt(f.getName());
        if (matched.cgi.containsKey(ext) || cfg.getRoutes().values().stream().anyMatch(r -> r.cgi.containsKey(ext))) {
            // run cgi
            return cgi.execute(req, f);
        }
        // methods: GET -> serve file, DELETE -> delete file, POST -> accept upload if
        // multipart
        switch (req.method) {
            case "GET":
                return HTTPResponse.serveFile(f, true);
            case "DELETE":
                if (f.delete()) {
                    return HTTPResponse.simple(200, "Deleted");
                } else {
                    return HTTPResponse.error(500, cfg);
                }
            case "POST":
                if (req.tempFilePath == null || !Files.exists(req.tempFilePath)) {
                    return HTTPResponse.error(500, cfg);
                }

                // 1. Read first 4KB to find end of headers (\r\n\r\n)
                byte[] headerBuf = new byte[4096];
                int headerLen = (int) Math.min(4096, Files.size(req.tempFilePath));
                try (FileChannel fc = FileChannel.open(req.tempFilePath, StandardOpenOption.READ)) {
                    fc.read(ByteBuffer.wrap(headerBuf), 0);
                }

                int headerEnd = -1;
                for (int i = 0; i <= headerLen - 4; i++) {
                    if (headerBuf[i] == '\r' && headerBuf[i + 1] == '\n'
                            && headerBuf[i + 2] == '\r' && headerBuf[i + 3] == '\n') {
                        headerEnd = i + 4; // skip \r\n\r\n
                        break;
                    }
                }
                if (headerEnd == -1) {
                    return HTTPResponse.error(400, cfg);
                }

                // 2. Extract filename
                String headers = new String(headerBuf, 0, headerEnd, StandardCharsets.ISO_8859_1);
                String filename = "upload.bin";
                int fnIdx = headers.indexOf("filename=\"");
                if (fnIdx != -1) {
                    int start = fnIdx + 10;
                    int end = headers.indexOf('"', start);
                    if (end > start) {
                        filename = headers.substring(start, end);
                    }
                }

                // 3. ✅ CRITICAL: Use Content-Length for body size
                long bodySize = req.expectedContentLength; // ← from HTTPParser
                if (bodySize <= 0) {
                    return HTTPResponse.error(400, cfg);
                }

                // 4. Save body: [headerEnd, headerEnd + bodySize)
                String safeName = filename.replaceAll("[^a-zA-Z0-9.\\-_ ]", "_").replaceAll("\\s+", "_");
                Path out = Path.of("upload", safeName).toAbsolutePath();
                Files.createDirectories(out.getParent());

                try (var src = open(req.tempFilePath, StandardOpenOption.READ); FileChannel dest = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    src.position(headerEnd);
                    long transferred = 0;
                    if (bodySize <= 0) {
                        System.err.println("⚠️ bodySize = " + bodySize + ", skipping transfer");
                        // Still create empty file
                        Files.write(out, new byte[0]);
                    } else {
                        while (transferred < bodySize) {
                            long chunk = Math.min(bodySize - transferred, 65536);
                            if (chunk <= 0) {
                                break; // safety

                            }
                            long written = dest.transferFrom(src, transferred, chunk);
                            if (written <= 0) {
                                break; // EOF

                            }
                            transferred += written;
                        }
                        System.out.println("✅ Transferred: " + transferred + "/" + bodySize);
                    }
                }

                System.out.println("✅ Saved " + safeName + " (" + bodySize + " bytes)");
                return HTTPResponse.simple(200, "OK");
            default:
                return HTTPResponse.error(405, cfg);
        }
    }

    private String getExt(String n) {
        int i = n.lastIndexOf('.');
        return i == -1 ? "" : n.substring(i + 1);
    }
}
