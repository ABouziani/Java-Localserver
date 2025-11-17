import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import javax.net.ssl.*;

public class Main {
    public static void main(String[] args) throws Exception {
        String cfgPath = (args.length>0)? args[0] : "config.json";
        Config config = ConfigLoader.load(cfgPath);
        Server server = new Server(config);
        server.start();
    }
}

/* ------------------ ConfigLoader.java ------------------ */
class ConfigLoader {
    static Config load(String path) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        // Very small JSON parser to avoid external deps. Assumes config is clean.
        Map<String,Object> m = TinyJson.parse(text);
        return Config.fromMap(m);
    }
}

/* ------------------ Config.java ------------------ */
class Config {
    public List<Integer> ports = List.of(8080);
    public String host = "0.0.0.0";
    public String root = "www";
    public Map<String,String> errorPages = new HashMap<>();
    public int clientMaxBody = 10 * 1024 * 1024; // 10MB default
    public Map<String,Route> routes = new HashMap<>();

    static Config fromMap(Map<String,Object> m) {
        Config c = new Config();
        if (m.containsKey("ports")) {
            ArrayList<Object> a = (ArrayList<Object>) m.get("ports");
            c.ports = new ArrayList<>();
            for (Object o: a) c.ports.add(((Number)o).intValue());
        }
        if (m.containsKey("host")) c.host = (String)m.get("host");
        if (m.containsKey("root")) c.root = (String)m.get("root");
        if (m.containsKey("clientMaxBody")) c.clientMaxBody = ((Number)m.get("clientMaxBody")).intValue();
        if (m.containsKey("errorPages")) {
            Map<String,Object> ep = (Map<String,Object>) m.get("errorPages");
            for (var e: ep.entrySet()) c.errorPages.put(e.getKey(), (String)e.getValue());
        }
        if (m.containsKey("routes")) {
            Map<String,Object> rs = (Map<String,Object>) m.get("routes");
            for (var en: rs.entrySet()) {
                c.routes.put(en.getKey(), Route.fromMap((Map<String,Object>)en.getValue()));
            }
        }
        return c;
    }
}

class Route {
    public Set<String> methods = new HashSet<>();
    public String root = null;
    public String index = "index.html";
    public boolean dirList = false;
    public Map<String,String> cgi = new HashMap<>();
    public String redirect = null;

    static Route fromMap(Map<String,Object> m) {
        Route r = new Route();
        if (m.containsKey("methods")) {
            ArrayList<Object> a = (ArrayList<Object>) m.get("methods");
            for (Object o: a) r.methods.add(((String)o).toUpperCase());
        }
        if (m.containsKey("root")) r.root = (String)m.get("root");
        if (m.containsKey("index")) r.index = (String)m.get("index");
        if (m.containsKey("dirList")) r.dirList = (Boolean)m.get("dirList");
        if (m.containsKey("cgi")) {
            Map<String,Object> cc = (Map<String,Object>) m.get("cgi");
            for (var e: cc.entrySet()) r.cgi.put(e.getKey(), (String)e.getValue());
        }
        if (m.containsKey("redirect")) r.redirect = (String)m.get("redirect");
        return r;
    }
}

/* ------------------ TinyJson.java (very small parser) ------------------ */
class TinyJson {
    // WARNING: this is a tiny, tolerant parser for simple config files (objects, arrays, numbers, strings, booleans, null)
    static Map<String,Object> parse(String s) {
        return (Map<String,Object>) new Parser(s).parseValue();
    }
    static class Parser {
        String s; int i=0; int n;
        Parser(String s){this.s=s; n=s.length();}
        void ws(){while(i<n && Character.isWhitespace(s.charAt(i))) i++;}
        Object parseValue(){ ws(); if(i>=n) return null; char c=s.charAt(i);
            if (c=='{') return parseObj();
            if (c=='[') return parseArray();
            if (c=='"') return parseString();
            if (c=='t' || c=='f') return parseBool();
            if (c=='n') {i+=4; return null;}
            return parseNumber();
        }
        Map<String,Object> parseObj(){ Map<String,Object> m = new HashMap<>(); i++; ws(); while(i<n && s.charAt(i)!='}'){
            String k = parseString(); ws(); if(s.charAt(i)==':') i++; ws(); Object v = parseValue(); m.put(k,v); ws(); if(s.charAt(i)==',') {i++; ws();} }
            i++; return m; }
        ArrayList<Object> parseArray(){ ArrayList<Object> a = new ArrayList<>(); i++; ws(); while(i<n && s.charAt(i)!=']'){ a.add(parseValue()); ws(); if(s.charAt(i)==','){i++; ws();} } i++; return a; }
        String parseString(){ StringBuilder b=new StringBuilder(); i++; while(i<n){char c=s.charAt(i++); if(c=='"') break; if(c=='\\'){ if(i<n){char e=s.charAt(i++); b.append(e);} } else b.append(c);} return b.toString(); }
        Object parseBool(){ if (s.startsWith("true", i)) {i+=4; return Boolean.TRUE;} i+=5; return Boolean.FALSE; }
        Number parseNumber(){ int start=i; while(i<n && "0123456789+-.eE".indexOf(s.charAt(i))>=0) i++; String t = s.substring(start,i); if(t.contains(".")|| t.contains("e")||t.contains("E")) return Double.parseDouble(t); return Long.parseLong(t); }
    }
}

/* ------------------ Server.java ------------------ */
class Server {
    private final Config cfg;
    private final Selector selector;
    private final Map<SocketChannel, ClientState> clients = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Router router;
    private final CGIHandler cgi;

    public Server(Config cfg) throws IOException {
        this.cfg = cfg;
        this.selector = Selector.open();
        this.router = new Router(cfg);
        this.cgi = new CGIHandler(cfg);
        setupListeners();
    }

    private void setupListeners() throws IOException {
        for (int port : cfg.ports) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(cfg.host, port));
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on " + cfg.host + ":" + port);
        }
    }

    public void start() {
        System.out.println("Server started (single-threaded NIO loop)");
        try {
            while (true) {
                selector.select(1000); // timeout to allow housekeeping
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    try {
                        if (k.isAcceptable()) accept(k);
                        else if (k.isReadable()) read(k);
                        else if (k.isWritable()) write(k);
                    } catch (CancelledKeyException cke) { continue; }
                }
                houseKeeping();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel sc = ssc.accept();
        if (sc==null) return;
        sc.configureBlocking(false);
        sc.socket().setSoTimeout(0);
        sc.register(selector, SelectionKey.OP_READ);
        clients.put(sc, new ClientState(sc));
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        ClientState st = clients.get(sc);
        if (st==null) { sc.close(); return; }
        int r = sc.read(st.readBuf);
        if (r==-1) { closeClient(sc); return; }
        st.lastActive = System.currentTimeMillis();
        st.readBuf.flip();
        byte[] b = new byte[st.readBuf.remaining()]; st.readBuf.get(b); st.readBuf.clear();
        st.in.append(new String(b, StandardCharsets.UTF_8));
        // parse requests (support pipelining simple)
        while (true) {
            HTTPRequest req = HTTPParser.tryParse(st.in.toString());
            if (req==null) break;
            st.in = new StringBuilder(req.remain);
            // cap body size
            if (req.body != null && req.body.length() > cfg.clientMaxBody) {
                enqueueResponse(sc, HTTPResponse.error(413, cfg));
                continue;
            }
            try {
                handleRequest(sc, req);
            } catch (Throwable t) {
                t.printStackTrace();
                enqueueResponse(sc, HTTPResponse.error(500, cfg));
            }
        }
    }

    private void handleRequest(SocketChannel sc, HTTPRequest req) throws IOException {
        // sessions
        String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie",""), "SID");
        Session sess = null;
        if (sid != null) sess = sessions.get(sid);
        if (sess == null) {
            sid = UUID.randomUUID().toString();
            sess = new Session(sid);
            sessions.put(sid, sess);
        }

        HTTPResponse resp = router.route(req, sess, cgi);
        // set session cookie
        resp.headers.put("Set-Cookie", "SID="+sid+"; Path=/; HttpOnly");
        enqueueResponse(sc, resp);
    }

    private void enqueueResponse(SocketChannel sc, HTTPResponse resp) throws IOException {
        ClientState st = clients.get(sc);
        if (st==null) return;
        st.pending.add(resp);
        sc.register(selector, SelectionKey.OP_WRITE);
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        ClientState st = clients.get(sc);
        if (st==null) { sc.close(); return; }
        if (st.pending.isEmpty()) { sc.register(selector, SelectionKey.OP_READ); return; }
        HTTPResponse resp = st.pending.peek();
        if (!resp.sentHeader) {
            byte[] h = resp.toHeaderBytes();
            st.writeBuf = ByteBuffer.wrap(h);
            resp.sentHeader = true;
        }
        if (st.writeBuf!=null && st.writeBuf.hasRemaining()) {
            sc.write(st.writeBuf);
            if (st.writeBuf.hasRemaining()) return; // partial
            st.writeBuf = null;
        }
        if (!resp.streamed) {
            if (resp.body != null) {
                st.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
                sc.write(st.writeBuf);
                if (st.writeBuf.hasRemaining()) return; // partial
                st.writeBuf = null;
            }
            st.pending.poll();
            if (!resp.keepAlive) { closeClient(sc); return; }
        } else {
            // streamed body (file)
            if (resp.fileChannel == null) {
                resp.fileChannel = FileChannel.open(resp.file.toPath(), StandardOpenOption.READ);
                resp.filePos = 0;
            }
            long transferred = resp.fileChannel.transferTo(resp.filePos, 8192, sc);
            resp.filePos += transferred;
            if (resp.filePos >= resp.file.length()) {
                resp.fileChannel.close();
                st.pending.poll();
                if (!resp.keepAlive) { closeClient(sc); return; }
            }
        }
        // if more pending, keep OP_WRITE
        if (!st.pending.isEmpty()) sc.register(selector, SelectionKey.OP_WRITE);
        else sc.register(selector, SelectionKey.OP_READ);
    }

    private void closeClient(SocketChannel sc) throws IOException {
        clients.remove(sc);
        sc.close();
    }

    private void houseKeeping() {
        // expire sessions (10m)
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now - s.lastAccessed > 10*60*1000);
        // timeouts for clients
        List<SocketChannel> toClose = new ArrayList<>();
        for (var e: clients.entrySet()) {
            if (now - e.getValue().lastActive > 30*1000) toClose.add(e.getKey());
        }
        for (var sc: toClose) try { closeClient(sc); } catch (IOException ex) {}
    }
}

class ClientState {
    public SocketChannel ch;
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(8*1024);
    public ByteBuffer writeBuf = null;
    public StringBuilder in = new StringBuilder();
    public Queue<HTTPResponse> pending = new ArrayDeque<>();
    public long lastActive = System.currentTimeMillis();
    public boolean closed = false;
    public ClientState(SocketChannel ch){this.ch=ch;}
}

/* ------------------ Router.java ------------------ */
class Router {
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

/* ------------------ HTTPParser.java ------------------ */
class HTTPParser {
    // returns null if incomplete. If complete, returns HTTPRequest with remain string (unparsed tail)
    static HTTPRequest tryParse(String raw) {
        int headersEnd = raw.indexOf("\r\n\r\n");
        if (headersEnd==-1) return null;
        String head = raw.substring(0, headersEnd+4);
        String[] lines = head.split("\r\n");
        String[] start = lines[0].split(" ",3);
        if (start.length<3) return HTTPRequest.invalid();
        String method = start[0]; String path = start[1]; String ver = start[2];
        Map<String,String> headers = new HashMap<>();
        for (int i=1;i<lines.length;i++){
            String line = lines[i]; if (line.isEmpty()) continue; int idx=line.indexOf(':'); if (idx==-1) continue; String k=line.substring(0,idx); String v=line.substring(idx+1).trim(); headers.put(k,v);
        }
        int contentLength = headers.containsKey("Content-Length") ? Integer.parseInt(headers.get("Content-Length")) : -1;
        boolean chunked = "chunked".equalsIgnoreCase(headers.getOrDefault("Transfer-Encoding",""));
        int totalLen = headersEnd+4;
        String body = null; String remain = raw.substring(totalLen);
        if (chunked) {
            // need to parse chunks: look for terminating 0\r\n\r\n
            int term = raw.indexOf("\r\n0\r\n\r\n", totalLen-0);
            if (term==-1) return null;
            String chunkPart = raw.substring(totalLen, term+5);
            body = decodeChunks(chunkPart);
            remain = raw.substring(term+7);
        } else if (contentLength>=0) {
            if (remain.length() < contentLength) return null;
            body = remain.substring(0, contentLength);
            remain = remain.substring(contentLength);
        }
        HTTPRequest req = new HTTPRequest();
        req.method = method; req.path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        req.version = ver; req.headers = headers; req.body = body; req.valid = true; req.remain = remain;
        return req;
    }
    static String decodeChunks(String s) {
        StringBuilder out = new StringBuilder(); int i=0; while (i<s.length()){
            int cr = s.indexOf('\r', i); if (cr==-1) break; String lenHex = s.substring(i,cr); int len = Integer.parseInt(lenHex.trim(), 16); if (len==0) break; i = cr+2; out.append(s.substring(i, i+len)); i+=len+2; }
        return out.toString();
    }
}

class HTTPRequest { String method,path,version; Map<String,String> headers=new HashMap<>(); String body; boolean valid=false; String remain=""; static HTTPRequest invalid(){HTTPRequest r=new HTTPRequest(); r.valid=false; return r;} }

/* ------------------ HTTPResponse.java ------------------ */
class HTTPResponse {
    int status; Map<String,String> headers = new HashMap<>(); String body = null; boolean streamed = false; File file = null; boolean sentHeader=false; boolean keepAlive=true; FileChannel fileChannel=null; long filePos=0;
    static HTTPResponse error(int code, Config cfg) {
        HTTPResponse r = new HTTPResponse(); r.status=code; r.headers.put("Connection","close"); r.keepAlive=false;
        String path = cfg.errorPages.getOrDefault(String.valueOf(code), null);
        if (path!=null && Files.exists(Paths.get(path))) { r.streamed=true; r.file = new File(path); r.headers.put("Content-Length", String.valueOf(r.file.length())); r.headers.put("Content-Type","text/html"); }
        else { r.body = "<html><body><h1>"+code+"</h1></body></html>"; r.headers.put("Content-Length", String.valueOf(r.body.length())); r.headers.put("Content-Type","text/html"); }
        return r;
    }
    static HTTPResponse redirect(String to) {
        HTTPResponse r = new HTTPResponse(); r.status=302; r.headers.put("Location", to); r.body = ""; r.headers.put("Content-Length","0"); return r;
    }
    static HTTPResponse serveFile(File f, boolean keepAlive) {
        HTTPResponse r = new HTTPResponse(); r.status=200; r.streamed=true; r.file = f; r.keepAlive = keepAlive; r.headers.put("Content-Length", String.valueOf(f.length())); r.headers.put("Content-Type", guessMime(f.getName())); return r;
    }
    static HTTPResponse dirListing(File f) {
        StringBuilder b = new StringBuilder(); b.append("<html><body><ul>"); for (var x: f.listFiles()) { b.append("<li><a href='"+x.getName()+"'>"+x.getName()+"</a></li>"); } b.append("</ul></body></html>"); HTTPResponse r = new HTTPResponse(); r.body = b.toString(); r.status=200; r.headers.put("Content-Length", String.valueOf(r.body.length())); r.headers.put("Content-Type","text/html"); return r;
    }
    static HTTPResponse simple(int code, String text) { HTTPResponse r = new HTTPResponse(); r.status=code; r.body=text; r.headers.put("Content-Length", String.valueOf(text.length())); r.headers.put("Content-Type","text/plain"); return r; }
    byte[] toHeaderBytes() {
        StringBuilder b = new StringBuilder(); b.append("HTTP/1.1 ").append(status).append(" ").append(statusText(status)).append("\r\n");
        headers.putIfAbsent("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT"))));
        if (keepAlive) headers.putIfAbsent("Connection", "keep-alive"); else headers.putIfAbsent("Connection","close");
        for (var e: headers.entrySet()) b.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        b.append("\r\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }
    static String statusText(int s) { 
        switch(s){
            case 200:return"OK";
        case 302:return"Found";case 400:return"Bad Request";case 403:return"Forbidden";case 404:return"Not Found";case 405:return"Method Not Allowed";case 413:return"Payload Too Large";case 500:return"Internal Server Error";default:return"";} }
    static String guessMime(String name){ if (name.endsWith(".html")||name.endsWith(".htm")) return "text/html"; if (name.endsWith(".css")) return "text/css"; if (name.endsWith(".js")) return "application/javascript"; if (name.endsWith(".png")) return "image/png"; if (name.endsWith(".jpg")||name.endsWith(".jpeg")) return "image/jpeg"; return "application/octet-stream"; }
}

/* ------------------ Cookie & Session utils ------------------ */
class Cookie {
    static String getCookie(String header, String name) {
        if (header==null) return null;
        String[] parts = header.split(";");
        for (String p: parts) { String[] kv = p.trim().split("=",2); if (kv.length==2 && kv[0].equals(name)) return kv[1]; }
        return null;
    }
}
class Session { String id; long lastAccessed = System.currentTimeMillis(); Map<String,String> data = new HashMap<>(); Session(String id){this.id=id;} }

/* ------------------ Multipart parser (simple) ------------------ */
class MultipartForm {
    static class FilePart { String name, filename; byte[] data; }
    List<FilePart> files = new ArrayList<>();
    static MultipartForm parse(HTTPRequest req) throws IOException {
        MultipartForm mf = new MultipartForm();
        String ct = req.headers.getOrDefault("Content-Type","");
        int i = ct.indexOf("boundary="); if (i==-1) return mf; String b = "--"+ct.substring(i+9);
        byte[] body = req.body!=null ? req.body.getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        String all = new String(body, StandardCharsets.ISO_8859_1);
        String[] chunks = all.split(Pattern.quote(b));
        for (String ch: chunks) {
            if (ch.trim().isEmpty() || ch.equals("--")) continue;
            int hEnd = ch.indexOf("\r\n\r\n"); if (hEnd==-1) continue;
            String hdr = ch.substring(0,hEnd);
            String data = ch.substring(hEnd+4, ch.length()-2);
            String fn = null; String name = null;
            for (String line: hdr.split("\r\n")) {
                if (line.startsWith("Content-Disposition")) {
                    int fnIdx = line.indexOf("filename=\""); if (fnIdx!=-1) { fn = line.substring(fnIdx+10, line.indexOf('"', fnIdx+10)); }
                    int nmIdx = line.indexOf("name=\""); if (nmIdx!=-1) { name = line.substring(nmIdx+6, line.indexOf('"', nmIdx+6)); }
                }
            }
            if (fn!=null) {
                FilePart p = new FilePart(); p.name=name; p.filename=fn; p.data=data.getBytes(StandardCharsets.ISO_8859_1); mf.files.add(p);
            }
        }
        return mf;
    }
}

/* ------------------ CGIHandler.java ------------------ */
class CGIHandler {
    private final Config cfg;
    CGIHandler(Config cfg){ this.cfg=cfg; }
    HTTPResponse execute(HTTPRequest req, File file) throws IOException {
        // Only allowing .py as configured example
        String ext = file.getName().contains(".") ? file.getName().substring(file.getName().lastIndexOf('.')+1) : "";
        String interpreter = "python3"; // default; can be configurable
        ProcessBuilder pb = new ProcessBuilder(interpreter, file.getAbsolutePath());
        Map<String,String> env = pb.environment();
        env.put("REQUEST_METHOD", req.method);
        env.put("PATH_INFO", file.getAbsolutePath());
        req.headers.forEach((k,v)-> env.put("HTTP_"+k.toUpperCase().replace('-', '_'), v));
        Process p = pb.start();
        if (req.body!=null) {
            try(OutputStream os = p.getOutputStream()) { os.write(req.body.getBytes(StandardCharsets.UTF_8)); }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(InputStream is = p.getInputStream()) { is.transferTo(baos); }
        try(InputStream es = p.getErrorStream()) { es.transferTo(new ByteArrayOutputStream()); }
        try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) {}
        String out = baos.toString(StandardCharsets.UTF_8);
        // Expect CGI returns full HTTP headers + body or just body. We handle simply.
        int sep = out.indexOf("\r\n\r\n");
        HTTPResponse r = new HTTPResponse();
        if (sep!=-1) {
            String hdrs = out.substring(0,sep);
            String body = out.substring(sep+4);
            for (String line: hdrs.split("\r\n")) { int c = line.indexOf(':'); if (c>0) r.headers.put(line.substring(0,c), line.substring(c+1).trim()); }
            r.body=body; r.status=200; r.headers.putIfAbsent("Content-Length", String.valueOf(r.body.length()));
        } else { r.body = out; r.status=200; r.headers.put("Content-Length", String.valueOf(r.body.length())); }
        return r;
    }
}

/* ------------------ error pages and README (examples) ------------------ */
// config.json (example) -- create next to jar
/*
{
  "ports": [8080, 8081],
  "host": "0.0.0.0",
  "root": "www",
  "clientMaxBody": 10485760,
  "errorPages": {"404":"error_pages/404.html","500":"error_pages/500.html"},
  "routes": {
    "/": {"methods":["GET","POST","DELETE"], "root":"www", "index":"index.html", "dirList":true, "cgi":{"py":"python3"}}
  }
}
*/

// README: Build and run instructions
/*
1) javac -d out LocalServer.java
2) mkdir www && mkdir error_pages
3) create config.json as example above
4) java -cp out Main config.json
*/