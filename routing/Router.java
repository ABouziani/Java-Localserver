package routing;

public class Router {
    private final Config cfg;
    Router(Config cfg){ this.cfg = cfg; }

    HTTPResponse route(HTTPRequest req, Session sess, CGIHandler cgi) throws IOException {
        // basic validation
        if (!req.valid) return HTTPResponse.error(400, cfg);
        // find route by prefix (simple longest match)
        Route matched = null; String matchedPath="";
        for (var en: cfg.routes.entrySet()) {
            String p = en.getKey();
            if (req.path.startsWith(p) && p.length()>=matchedPath.length()) { matched=en.getValue(); matchedPath=p; }
        }
        if (matched==null) matched = new Route(); matched.root = cfg.root;
        // redirect
        if (matched.redirect != null) {
            return HTTPResponse.redirect(matched.redirect);
        }
        if (!matched.methods.isEmpty() && !matched.methods.contains(req.method)) {
            return HTTPResponse.error(405, cfg);
        }
        // map path
        Path fsroot = Paths.get(matched.root != null ? matched.root : cfg.root);
        String rel = req.path.substring(matchedPath.length());
        if (rel.equals("") || rel.equals("/")) rel = "/"+matched.index;
        Path target = fsroot.resolve(rel.substring(1)).normalize();
        if (!target.startsWith(fsroot)) return HTTPResponse.error(403, cfg);
        File f = target.toFile();
        if (f.isDirectory()) {
            File idx = new File(f, matched.index);
            if (idx.exists()) return HTTPResponse.serveFile(idx, true);
            if (matched.dirList) return HTTPResponse.dirListing(f);
            return HTTPResponse.error(403, cfg);
        }
        if (!f.exists()) return HTTPResponse.error(404, cfg);
        // CGI handling by extension
        String ext = getExt(f.getName());
        if (matched.cgi.containsKey(ext) || cfg.routes.values().stream().anyMatch(r->r.cgi.containsKey(ext))) {
            // run cgi
            return cgi.execute(req, f);
        }
        // methods: GET -> serve file, DELETE -> delete file, POST -> accept upload if multipart
        switch (req.method) {
            case "GET": return HTTPResponse.serveFile(f, true);
            case "DELETE": if (f.delete()) return HTTPResponse.simple(200, "Deleted"); else return HTTPResponse.error(500, cfg);
            case "POST": // naive upload: if body present and content-type multipart/form-data
                String ct = req.headers.getOrDefault("Content-Type", "");
                if (ct.startsWith("multipart/form-data")) {
                    MultipartForm mf = MultipartForm.parse(req);
                    for (var part: mf.files) {
                        Path out = Paths.get(matched.root, part.filename).normalize();
                        Files.write(out, part.data);
                    }
                    return HTTPResponse.simple(200, "Uploaded");
                }
                return HTTPResponse.simple(200, req.body!=null?req.body:"");
            default: return HTTPResponse.error(405, cfg);
        }
    }
    private String getExt(String n) { int i = n.lastIndexOf('.'); return i==-1?"":n.substring(i+1); }
}