package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cgi.CGIHandler;
import client.ClientState;
import configurations.Config;
import configurations.Listen;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;
import routing.Router;
import session.Cookie;
import session.Session;

public class Server {
    private final Config cfg;
    private final Map<SocketChannel, ClientState> clients = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    
    private final Router router;
    private final CGIHandler cgi;


    public Server(Config cfg) throws IOException {
        MainServer.selector = Selector.open();

        this.cfg = cfg;
        this.router = new Router(cfg);
        this.cgi = new CGIHandler(cfg);
        setupListeners();
    }

    private void setupListeners() throws IOException {
        for (Listen listen : cfg.listens) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(listen.getIp(), listen.getPort()));
            ssc.register(MainServer.selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on " + listen.getIp() + ":" + listen.getPort());
        }
    }

    
    

    

    public void handleRequest(SocketChannel sc, HTTPRequest req) throws IOException {
        // sessions
        clients.put(sc, new ClientState(sc));
        String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
        Session sess = null;
        if (sid != null)
            sess = sessions.get(sid);
        if (sess == null) {
            sid = UUID.randomUUID().toString();
            sess = new Session(sid);
            sessions.put(sid, sess);
        }

        HTTPResponse resp = router.route(req, sess, cgi);
        // set session cookie
        resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
        enqueueResponse(sc, resp);
    }

    public void enqueueResponse(SocketChannel sc, HTTPResponse resp) throws IOException {
        ClientState st = clients.get(sc);
        if (st == null)
            return;
        st.pending.add(resp);
        sc.register(MainServer.selector, SelectionKey.OP_WRITE);
    }

    public void read(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        ClientState st = clients.get(sc);
        if (st == null) {
            sc.close();
            return;
        }
        int r = sc.read(st.readBuf);
        if (r == -1) {
            MainServer.closeClient(clients,sc);
            return;
        }
        st.lastActive = System.currentTimeMillis();
        st.readBuf.flip();
        byte[] b = new byte[st.readBuf.remaining()];
        st.readBuf.get(b);
        st.readBuf.clear();
        st.in.append(new String(b, StandardCharsets.UTF_8));
        // parse requests (support pipelining simple)
        while (true) {
            HTTPRequest req = HTTPParser.tryParse(st.in.toString());
            if (req == null)
                break;
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

    public void write(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        ClientState st = clients.get(sc);
        if (st == null) {
            sc.close();
            return;
        }
        if (st.pending.isEmpty()) {
            sc.register(MainServer.selector, SelectionKey.OP_READ);
            return;
        }
        HTTPResponse resp = st.pending.peek();
        if (!resp.sentHeader) {
            byte[] h = resp.toHeaderBytes();
            st.writeBuf = ByteBuffer.wrap(h);
            resp.sentHeader = true;
        }
        if (st.writeBuf != null && st.writeBuf.hasRemaining()) {
            sc.write(st.writeBuf);
            if (st.writeBuf.hasRemaining())
                return; // partial
            st.writeBuf = null;
        }
        if (!resp.streamed) {
            if (resp.body != null) {
                st.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
                sc.write(st.writeBuf);
                if (st.writeBuf.hasRemaining())
                    return; // partial
                st.writeBuf = null;
            }
            st.pending.poll();
            if (!resp.keepAlive) {
                MainServer.closeClient(clients,sc);
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
                st.pending.poll();
                if (!resp.keepAlive) {
                    MainServer.closeClient(clients,sc);
                    return;
                }
            }
        }
        // if more pending, keep OP_WRITE
        if (!st.pending.isEmpty())
            sc.register(MainServer.selector, SelectionKey.OP_WRITE);
        else
            sc.register(MainServer.selector, SelectionKey.OP_READ);
    }

    public Config getCfg() {
        return cfg;
    }

    
    private void houseKeeping() {
        // expire sessions (10m)
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now - s.lastAccessed > 10 * 60 * 1000);
        // timeouts for clients
        List<SocketChannel> toClose = new ArrayList<>();
        for (var e : clients.entrySet()) {
            if (now - e.getValue().lastActive > 30 * 1000)
                toClose.add(e.getKey());
        }
        for (var sc : toClose)
            try {
                MainServer.closeClient(clients,sc);
            } catch (IOException ex) {
            }
    }

    public Map<SocketChannel, ClientState> getClients() {
        return clients;
    }

    public Map<String, Session> getSessions() {
        return sessions;
    }

}