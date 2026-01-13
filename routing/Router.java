package routing;

import cgi.CGIHandler;
import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;
import parsing.MultipartForm;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import static java.nio.ByteBuffer.wrap;
import java.nio.channels.FileChannel;
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
            return HTTPResponse.errorWithBody(404, "Path not found", cfg);
        }
        // redirect

        if (!matched.methods.isEmpty() && !matched.methods.contains(req.method)) {
            return HTTPResponse.error(405, cfg);
        }
        // map path
        Path fsroot = Paths.get(matched.root != null ? matched.root : cfg.getRoot());
        String rel = req.path.substring(matchedPath.length());
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path target = fsroot.resolve(rel).normalize();
        System.out.println(target + fsroot.toString());
        if (!target.startsWith(fsroot)) {
            return HTTPResponse.error(403, cfg);
        }
        File f = target.toFile();

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
                if (f.isDirectory()) {
                    File idx = new File(f, matched.index);
                    if (idx.exists()) {
                        return HTTPResponse.serveFile(idx, true);
                    }
                    if (matched.dirList) {
                        return HTTPResponse.dirListing(f, rel);
                    }
                    if (matched.redirect != null) {
                        return HTTPResponse.redirect(matched.redirect);
                    }
                    return HTTPResponse.error(404, cfg);
                }
                if (!f.exists()) {
                    return HTTPResponse.error(404, cfg);
                }
                return HTTPResponse.serveFile(f, true);
            case "DELETE":
                if (f.isDirectory()) {
                    return HTTPResponse.error(403, cfg);
                }
                if (!f.exists()) {
                    return HTTPResponse.error(404, cfg);
                }
                if (f.delete()) {
                    return HTTPResponse.simple(200, sess, "Deleted");
                }
                if (matched.redirect != null) {
                    return HTTPResponse.redirect(matched.redirect);
                }
                return HTTPResponse.error(500, cfg);
            case "POST":
                String ct = req.headers.getOrDefault("content-type", "");
                // System.out.println(ct);
                if (ct.startsWith("multipart/form-data")) {
                    HTTPResponse resp = MultipartForm.parseBody(req, cfg, matched, sess);
                    if (resp != null) {
                        return resp;
                    }
                } else {
                    if (req.tempFilePath != null && Files.size(req.tempFilePath) != 0) {

                        byte[] fileBuf = new byte[(int) Files.size(req.tempFilePath)];
                        try (FileChannel fc = FileChannel.open(req.tempFilePath, StandardOpenOption.READ)) {
                            fc.read(wrap(fileBuf), 0);
                        }
                        return HTTPResponse.simple(200, sess, new String(fileBuf));
                    } else {
                        return HTTPResponse.simple(200, sess, "");
                    }
                }
                return HTTPResponse.simple(200, sess, "OK");
            default:
                return HTTPResponse.error(405, cfg);
        }
    }

    private String getExt(String n) {
        int i = n.lastIndexOf('.');
        return i == -1 ? "" : n.substring(i + 1);
    }

}
