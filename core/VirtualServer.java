package core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cgi.CGIHandler;
import client.ClientState;
import configurations.ServerConfig;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;
import configurations.Listen;
import routing.Route;
import routing.Router;
import session.Cookie;
import session.Session;

public class VirtualServer {
    private final ServerConfig config;
    private final MainServer mainServer;

    private final Router router;
    private final CGIHandler cgiHandler;
    private final Map<String, Session> sessions = new HashMap<>();

    private final List<Listen> listens;
    private final int clientMaxBody;
    private final String root;
    private final Map<String, String> errorPages;
    private final Map<String, Route> routes;
    private final List<String> hosts;
    private final boolean isDefault;

    public VirtualServer(ServerConfig config, MainServer mainServer) {
        this.config = config;
        this.mainServer = mainServer;

        this.listens = config.getListens();
        this.clientMaxBody = config.getClientMaxBody();
        this.root = config.getRoot();
        this.errorPages = config.getErrorPages();
        this.routes = config.getRoutes();
        this.hosts = config.getHosts();
        this.isDefault = config.getDefaultt();

        this.router = new Router(config);
        this.cgiHandler = new CGIHandler(config);
    }

    public void read(SocketChannel sc, ClientState state) throws IOException {

        int r = sc.read(state.readBuf);
        // if (r == -1) {
        // closeClient(sc);
        // return;
        // }
        state.lastActive = System.currentTimeMillis();
        state.readBuf.flip();
        byte[] b = new byte[state.readBuf.remaining()];
        state.readBuf.get(b);
        state.readBuf.clear();
        state.in.append(new String(b, StandardCharsets.UTF_8));
        // parse requests (support pipelining simple)
        while (true) {
            HTTPRequest req = HTTPParser.tryParse(state.in.toString());
            if (req == null)
                break;
            state.setCurrentRequest(req);
            state.in = new StringBuilder(req.remain);
            // cap body size
            if (req.body != null && req.body.length() > config.getClientMaxBody()) {
                mainServer.enqueueResponse(sc, HTTPResponse.error(413, config));
                continue;
            }
            try {
                handleRequest(sc, req, state);
            } catch (Throwable t) {
                t.printStackTrace();
                mainServer.enqueueResponse(sc, HTTPResponse.error(500, config));
            }
        }
    }

    public void write(SocketChannel sc, ClientState state) throws IOException {
       
        HTTPResponse resp = state.pending.peek();
        if (!resp.sentHeader) {
            byte[] h = resp.toHeaderBytes();
            state.writeBuf = ByteBuffer.wrap(h);
            resp.sentHeader = true;
        }
        if (state.writeBuf != null && state.writeBuf.hasRemaining()) {
            sc.write(state.writeBuf);
            if (state.writeBuf.hasRemaining())
                return; // partial
            state.writeBuf = null;
        }
        if (!resp.streamed) {
            if (resp.body != null) {
                state.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
                sc.write(state.writeBuf);
                if (state.writeBuf.hasRemaining())
                    return; // partial
                state.writeBuf = null;
            }
            state.pending.poll();
            if (!resp.keepAlive) {
                // closeClient(sc);
                return;
            }
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
                state.pending.poll();
                if (!resp.keepAlive) {
                    // closeClient(sc);
                    return;
                }
            }
        }
        
    }

    private void handleRequest(SocketChannel sc, HTTPRequest req, ClientState state) throws IOException {
        // sessions
        String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
        Session sess = null;
        if (sid != null)
            sess = sessions.get(sid);
        if (sess == null) {
            sid = UUID.randomUUID().toString();
            sess = new Session(sid);
            sessions.put(sid, sess);
        }

        HTTPResponse resp = router.route(req, sess, cgiHandler);
        // set session cookie
        resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
        mainServer.enqueueResponse(sc, resp);
    }
    //  private void closeClient(SocketChannel sc) throws IOException {
    //     clients.remove(sc);
    //     sc.close();
    // }

    // public void handleRequest(ClientState state, SelectionKey key) {
    // // Process request using this server's configuration
    // // Use this server's router, root, errorPages, etc.
    // router.route(state.getRequest(), state.getResponse());
    // }
    // public void handleRequest(SocketChannel sc, HTTPRequest req) throws
    // IOException {
    // // sessions
    // clients.put(sc, new ClientState(sc));
    // String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
    // Session sess = null;
    // if (sid != null)
    // sess = sessions.get(sid);
    // if (sess == null) {
    // sid = UUID.randomUUID().toString();
    // sess = new Session(sid);
    // sessions.put(sid, sess);
    // }

    // HTTPResponse resp = router.route(req, sess, cgi);
    // // set session cookie
    // resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
    // enqueueResponse(sc, resp);
    // }
    // public void handleRequest(SocketChannel sc, HTTPRequest req, ClientState
    // state) throws IOException {
    // // Session management
    // String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
    // Session sess = this.sessions.get(sid);

    // if (sess == null) {
    // sid = UUID.randomUUID().toString();
    // sess = new Session(sid);
    // sessions.put(sid, sess);
    // }

    // // Route the request using this server's router and configuration
    // HTTPResponse resp = router.route(req, sess, cgiHandler);

    // // Set session cookie
    // resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");

    // // Enqueue response (delegate back to MainServer)
    // mainServer.enqueueResponse(sc, resp, state);
    // }

    public List<Listen> getListens() {
        return listens;
    }

    public int getClientMaxBody() {
        return clientMaxBody;
    }

    public String getRoot() {
        return root;
    }

    public Map<String, String> getErrorPages() {
        return errorPages;
    }

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public Router getRouter() {
        return router;
    }

    public CGIHandler getCgiHandler() {
        return cgiHandler;
    }
}
/*
 * public class Server {
 * private final Config cfg;
 * private final Selector selector;
 * private final Map<SocketChannel, ClientState> clients = new HashMap<>();
 * private final Map<String, Session> sessions = new HashMap<>();
 * private final Router router;
 * private final CGIHandler cgi;
 * 
 * public Server(Config cfg) throws IOException {
 * this.cfg = cfg;
 * this.selector = Selector.open();
 * this.router = new Router(cfg);
 * this.cgi = new CGIHandler(cfg);
 * setupListeners();
 * }
 * 
 * private void setupListeners() throws IOException {
 * for (int port : cfg.ports) {
 * ServerSocketChannel ssc = ServerSocketChannel.open();
 * ssc.configureBlocking(false);
 * ssc.bind(new InetSocketAddress(cfg.host, port));
 * ssc.register(selector, SelectionKey.OP_ACCEPT);
 * System.out.println("Listening on " + cfg.host + ":" + port);
 * }
 * }
 * 
 * public void start() {
 * System.out.println("Server started (single-threaded NIO loop)");
 * try {
 * while (true) {
 * selector.select(1000); // timeout to allow housekeeping
 * Set<SelectionKey> keys = selector.selectedKeys();
 * Iterator<SelectionKey> it = keys.iterator();
 * while (it.hasNext()) {
 * SelectionKey k = it.next();
 * it.remove();
 * try {
 * if (k.isAcceptable())
 * accept(k);
 * else if (k.isReadable())
 * read(k);
 * else if (k.isWritable())
 * write(k);
 * } catch (CancelledKeyException cke) {
 * continue;
 * }
 * }
 * houseKeeping();
 * }
 * } catch (IOException e) {
 * e.printStackTrace();
 * }
 * }
 * 
 * private void accept(SelectionKey key) throws IOException {
 * ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
 * SocketChannel sc = ssc.accept();
 * if (sc == null)
 * return;
 * sc.configureBlocking(false);
 * sc.socket().setSoTimeout(0);
 * sc.register(selector, SelectionKey.OP_READ);
 * clients.put(sc, new ClientState(sc));
 * }
 * 
 * private void read(SelectionKey key) throws IOException {
 * SocketChannel sc = (SocketChannel) key.channel();
 * ClientState st = clients.get(sc);
 * if (st == null) {
 * sc.close();
 * return;
 * }
 * int r = sc.read(state.readBuf);
 * if (r == -1) {
 * closeClient(sc);
 * return;
 * }
 * st.lastActive = System.currentTimeMillis();
 * st.readBuf.flip();
 * byte[] b = new byte[st.readBuf.remaining()];
 * st.readBuf.get(b);
 * st.readBuf.clear();
 * st.in.append(new String(b, StandardCharsets.UTF_8));
 * // parse requests (support pipelining simple)
 * while (true) {
 * HTTPRequest req = HTTPParser.tryParse(st.in.toString());
 * if (req == null)
 * break;
 * st.in = new StringBuilder(req.remain);
 * // cap body size
 * if (req.body != null && req.body.length() > cfg.clientMaxBody) {
 * enqueueResponse(sc, HTTPResponse.error(413, cfg));
 * continue;
 * }
 * try {
 * handleRequest(sc, req);
 * } catch (Throwable t) {
 * t.printStackTrace();
 * enqueueResponse(sc, HTTPResponse.error(500, cfg));
 * }
 * }
 * }
 * 
 * private void handleRequest(SocketChannel sc, HTTPRequest req) throws
 * IOException {
 * // sessions
 * String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
 * Session sess = null;
 * if (sid != null)
 * sess = sessions.get(sid);
 * if (sess == null) {
 * sid = UUID.randomUUID().toString();
 * sess = new Session(sid);
 * sessions.put(sid, sess);
 * }
 * 
 * HTTPResponse resp = router.route(req, sess, cgi);
 * // set session cookie
 * resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
 * enqueueResponse(sc, resp);
 * }
 * 
 * private void enqueueResponse(SocketChannel sc, HTTPResponse resp) throws
 * IOException {
 * ClientState st = clients.get(sc);
 * if (st == null)
 * return;
 * st.pending.add(resp);
 * sc.register(selector, SelectionKey.OP_WRITE);
 * }
 * 
 * private void write(SelectionKey key) throws IOException {
 * SocketChannel sc = (SocketChannel) key.channel();
 * ClientState st = clients.get(sc);
 * if (st == null) {
 * sc.close();
 * return;
 * }
 * if (st.pending.isEmpty()) {
 * sc.register(selector, SelectionKey.OP_READ);
 * return;
 * }
 * HTTPResponse resp = st.pending.peek();
 * if (!resp.sentHeader) {
 * byte[] h = resp.toHeaderBytes();
 * st.writeBuf = ByteBuffer.wrap(h);
 * resp.sentHeader = true;
 * }
 * if (st.writeBuf != null && st.writeBuf.hasRemaining()) {
 * sc.write(st.writeBuf);
 * if (st.writeBuf.hasRemaining())
 * return; // partial
 * st.writeBuf = null;
 * }
 * if (!resp.streamed) {
 * if (resp.body != null) {
 * st.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
 * sc.write(st.writeBuf);
 * if (st.writeBuf.hasRemaining())
 * return; // partial
 * st.writeBuf = null;
 * }
 * st.pending.poll();
 * if (!resp.keepAlive) {
 * closeClient(sc);
 * return;
 * }
 * } else {
 * // streamed body (file)
 * if (resp.fileChannel == null) {
 * resp.fileChannel = FileChannel.open(resp.file.toPath(),
 * StandardOpenOption.READ);
 * resp.filePos = 0;
 * }
 * long transferred = resp.fileChannel.transferTo(resp.filePos, 8192, sc);
 * resp.filePos += transferred;
 * if (resp.filePos >= resp.file.length()) {
 * resp.fileChannel.close();
 * st.pending.poll();
 * if (!resp.keepAlive) {
 * closeClient(sc);
 * return;
 * }
 * }
 * }
 * // if more pending, keep OP_WRITE
 * if (!st.pending.isEmpty())
 * sc.register(selector, SelectionKey.OP_WRITE);
 * else
 * sc.register(selector, SelectionKey.OP_READ);
 * }
 * 
 * private void closeClient(SocketChannel sc) throws IOException {
 * clients.remove(sc);
 * sc.close();
 * }
 * 
 * private void houseKeeping() {
 * // expire sessions (10m)
 * long now = System.currentTimeMillis();
 * sessions.values().removeIf(s -> now - s.lastAccessed > 10 * 60 * 1000);
 * // timeouts for clients
 * List<SocketChannel> toClose = new ArrayList<>();
 * for (var e : clients.entrySet()) {
 * if (now - e.getValue().lastActive > 30 * 1000)
 * toClose.add(e.getKey());
 * }
 * for (var sc : toClose)
 * try {
 * closeClient(sc);
 * } catch (IOException ex) {
 * }
 * }
 * }
 */
// import java.io.IOException;
// import java.net.InetSocketAddress;
// import java.nio.ByteBuffer;
// import java.nio.channels.CancelledKeyException;
// import java.nio.channels.FileChannel;
// import java.nio.channels.SelectionKey;
// import java.nio.channels.Selector;
// import java.nio.channels.ServerSocketChannel;
// import java.nio.channels.SocketChannel;
// import java.nio.charset.StandardCharsets;
// import java.nio.file.StandardOpenOption;
// import java.util.ArrayList;
// import java.util.HashMap;
// import java.util.Iterator;
// import java.util.List;
// import java.util.Map;
// import java.util.Set;
// import java.util.UUID;

// import cgi.CGIHandler;
// import client.ClientState;
// import configurations.Config;
// import configurations.Listen;
// import http.HTTPParser;
// import http.HTTPRequest;
// import http.HTTPResponse;
// import routing.Router;
// import session.Cookie;
// import session.Session;

// public class Server {
// private final Config cfg;
// private final Map<SocketChannel, ClientState> clients = new HashMap<>();
// private final Map<String, Session> sessions = new HashMap<>();

// private final Router router;
// private final CGIHandler cgi;

// public Server(Config cfg) throws IOException {
// MainServer.selector = Selector.open();

// this.cfg = cfg;
// this.router = new Router(cfg);
// this.cgi = new CGIHandler(cfg);
// setupListeners();
// }

// private void setupListeners() throws IOException {
// for (Listen listen : cfg.listens) {
// ServerSocketChannel ssc = ServerSocketChannel.open();
// ssc.configureBlocking(false);
// ssc.bind(new InetSocketAddress(listen.getIp(), listen.getPort()));
// ssc.register(MainServer.selector, SelectionKey.OP_ACCEPT);
// System.out.println("Listening on " + listen.getIp() + ":" +
// listen.getPort());
// }
// }

// public void handleRequest(SocketChannel sc, HTTPRequest req) throws
// IOException {
// // sessions
// clients.put(sc, new ClientState(sc));
// String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
// Session sess = null;
// if (sid != null)
// sess = sessions.get(sid);
// if (sess == null) {
// sid = UUID.randomUUID().toString();
// sess = new Session(sid);
// sessions.put(sid, sess);
// }

// HTTPResponse resp = router.route(req, sess, cgi);
// // set session cookie
// resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
// enqueueResponse(sc, resp);
// }

// public void enqueueResponse(SocketChannel sc, HTTPResponse resp) throws
// IOException {
// ClientState st = clients.get(sc);
// if (st == null)
// return;
// st.pending.add(resp);
// sc.register(MainServer.selector, SelectionKey.OP_WRITE);
// }

// public void read(SelectionKey key) throws IOException {
// SocketChannel sc = (SocketChannel) key.channel();
// ClientState st = clients.get(sc);
// if (st == null) {
// sc.close();
// return;
// }
// int r = sc.read(st.readBuf);
// if (r == -1) {
// MainServer.closeClient(clients,sc);
// return;
// }
// st.lastActive = System.currentTimeMillis();
// st.readBuf.flip();
// byte[] b = new byte[st.readBuf.remaining()];
// st.readBuf.get(b);
// st.readBuf.clear();
// st.in.append(new String(b, StandardCharsets.UTF_8));
// // parse requests (support pipelining simple)
// while (true) {
// HTTPRequest req = HTTPParser.tryParse(st.in.toString());
// if (req == null)
// break;
// st.in = new StringBuilder(req.remain);
// // cap body size
// if (req.body != null && req.body.length() > cfg.clientMaxBody) {
// enqueueResponse(sc, HTTPResponse.error(413, cfg));
// continue;
// }
// try {
// handleRequest(sc, req);
// } catch (Throwable t) {
// t.printStackTrace();
// enqueueResponse(sc, HTTPResponse.error(500, cfg));
// }
// }
// }

// public void write(SelectionKey key) throws IOException {
// SocketChannel sc = (SocketChannel) key.channel();
// ClientState st = clients.get(sc);
// if (st == null) {
// sc.close();
// return;
// }
// if (st.pending.isEmpty()) {
// sc.register(MainServer.selector, SelectionKey.OP_READ);
// return;
// }
// HTTPResponse resp = st.pending.peek();
// if (!resp.sentHeader) {
// byte[] h = resp.toHeaderBytes();
// st.writeBuf = ByteBuffer.wrap(h);
// resp.sentHeader = true;
// }
// if (st.writeBuf != null && st.writeBuf.hasRemaining()) {
// sc.write(st.writeBuf);
// if (st.writeBuf.hasRemaining())
// return; // partial
// st.writeBuf = null;
// }
// if (!resp.streamed) {
// if (resp.body != null) {
// st.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
// sc.write(st.writeBuf);
// if (st.writeBuf.hasRemaining())
// return; // partial
// st.writeBuf = null;
// }
// st.pending.poll();
// if (!resp.keepAlive) {
// MainServer.closeClient(clients,sc);
// return;
// }
// } else {
// // streamed body (file)
// if (resp.fileChannel == null) {
// resp.fileChannel = FileChannel.open(resp.file.toPath(),
// StandardOpenOption.READ);
// resp.filePos = 0;
// }
// long transferred = resp.fileChannel.transferTo(resp.filePos, 8192, sc);
// resp.filePos += transferred;
// if (resp.filePos >= resp.file.length()) {
// resp.fileChannel.close();
// st.pending.poll();
// if (!resp.keepAlive) {
// MainServer.closeClient(clients,sc);
// return;
// }
// }
// }
// // if more pending, keep OP_WRITE
// if (!st.pending.isEmpty())
// sc.register(MainServer.selector, SelectionKey.OP_WRITE);
// else
// sc.register(MainServer.selector, SelectionKey.OP_READ);
// }

// public Config getCfg() {
// return cfg;
// }

// private void houseKeeping() {
// // expire sessions (10m)
// long now = System.currentTimeMillis();
// sessions.values().removeIf(s -> now - s.lastAccessed > 10 * 60 * 1000);
// // timeouts for clients
// List<SocketChannel> toClose = new ArrayList<>();
// for (var e : clients.entrySet()) {
// if (now - e.getValue().lastActive > 30 * 1000)
// toClose.add(e.getKey());
// }
// for (var sc : toClose)
// try {
// MainServer.closeClient(clients,sc);
// } catch (IOException ex) {
// }
// }

// public Map<SocketChannel, ClientState> getClients() {
// return clients;
// }

// public Map<String, Session> getSessions() {
// return sessions;
// }

// }