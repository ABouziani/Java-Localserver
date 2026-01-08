package routing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import cgi.CGIHandler;
import client.ClientState;
import configurations.ServerConfig;
import http.HTTPResponse;
import parsing.FilePart;
import session.Session;;

public class Router {
    private final ServerConfig cfg;

    public Router(ServerConfig cfg) {
        this.cfg = cfg;
    }

    public HTTPResponse route(ClientState state, Session sess, CGIHandler cgi) throws IOException {
        // basic validation
        if (!state.request.valid)
            return HTTPResponse.error(400, cfg);
        // find route by prefix (simple longest match)
        Route matched = null;
        String matchedPath = "";
        for (var en : cfg.getRoutes().entrySet()) {
            String p = en.getKey();
            if (state.request.path.startsWith(p) && p.length() >= matchedPath.length()) {
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
        if (!matched.methods.isEmpty() && !matched.methods.contains(state.request.method)) {
            return HTTPResponse.error(405, cfg);
        }
        // map path
        Path fsroot = Paths.get(matched.root != null ? matched.root : cfg.getRoot());
        String rel = state.request.path.substring(matchedPath.length());
        if (rel.equals("") || rel.equals("/"))
            rel = "/" + matched.index;
        Path target = fsroot.resolve(rel.substring(1)).normalize();
        if (!target.startsWith(fsroot))
            return HTTPResponse.error(403, cfg);
        File f = target.toFile();
        if (f.isDirectory()) {
            File idx = new File(f, matched.index);
            if (idx.exists())
                return HTTPResponse.serveFile(idx, true);
            if (matched.dirList)
                return HTTPResponse.dirListing(f);
            return HTTPResponse.error(403, cfg);
        }
        // if (!f.exists())
        // return HTTPResponse.error(404, cfg);
        // CGI handling by extension
        String ext = getExt(f.getName());
        if (matched.cgi.containsKey(ext) || cfg.getRoutes().values().stream().anyMatch(r -> r.cgi.containsKey(ext))) {
            // run cgi
            return cgi.execute(state.request, f);
        }

        switch (state.request.method) {
            case "GET":
                return HTTPResponse.serveFile(f, true);
            case "DELETE":
                if (f.delete())
                    return HTTPResponse.simple(200, "Deleted");
                else
                    return HTTPResponse.error(500, cfg);
            case "POST":
                state.fileOut.close();
                if (state.chunked){
                    return HTTPResponse.simple(200, "uploaded chunked");
                }
                FilePart part = FilePart.getIfAllSame(state.files);
                if (part == null) {
                    return HTTPResponse.error(400, cfg);
                }
                Path out = Paths.get(matched.root, part.filename).normalize();
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (state.tempFile != null && Files.exists(state.tempFile)) {
                    Files.move(state.tempFile, out, StandardCopyOption.REPLACE_EXISTING);
                    return HTTPResponse.simple(200, "uploaded");
                }

                return HTTPResponse.simple(200, "No data uploaded");
            
            default:
                return HTTPResponse.error(405, cfg);
        }
    }

    private String getExt(String n) {
        int i = n.lastIndexOf('.');
        return i == -1 ? "" : n.substring(i + 1);
    }
}
