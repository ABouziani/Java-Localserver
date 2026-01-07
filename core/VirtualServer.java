package core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import http.HTTPResponse;
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

    private final List<Integer> ports;
    private final int clientMaxBody;
    private final String root;
    private final Map<String, String> errorPages;
    private final Map<String, Route> routes;
    private final String host;
    private final boolean isDefault;

    public VirtualServer(ServerConfig config, MainServer mainServer) {
        this.config = config;
        this.mainServer = mainServer;

        this.ports = config.getPorts();
        this.clientMaxBody = config.getClientMaxBody();
        this.root = config.getRoot();
        this.errorPages = config.getErrorPages();
        this.routes = config.getRoutes();
        this.host = config.getHost();
        this.isDefault = config.getIsDefault();
        this.router = new Router(config);
        this.cgiHandler = new CGIHandler(config);
    }

    public void read(ClientState state, SocketChannel sc) throws IOException {
        // if (state.request.body != null && state.request.body.length() > config.getClientMaxBody()) {
        //     mainServer.enqueueResponse(sc, HTTPResponse.error(413, config));
        //     return;
        // }
        try {
            handleRequest(sc,state);
        } catch (Throwable t) {
            t.printStackTrace();
            mainServer.enqueueResponse(sc, HTTPResponse.error(500, config));
        }
    }

    public void write(SocketChannel sc, ClientState state) throws IOException {

        if (state.pending.isEmpty()) return;
        HTTPResponse resp = state.pending.peek();
        if (!resp.sentHeader) {
            byte[] h = resp.toHeaderBytes();
            state.writeBuf = ByteBuffer.wrap(h);
            resp.sentHeader = true;
        }
        if (state.writeBuf != null && state.writeBuf.hasRemaining()) {
            if(sc == null ||!sc.isOpen()) {
                
                System.out.print("----------sssssssssssssssssss--");
                mainServer.closeClient(sc);
            } else {
                sc.write(state.writeBuf);
                
            }
            // if (state.writeBuf.hasRemaining())
            state.writeBuf = null;
                return; // partial
        }
        
        if (!resp.streamed) {
            if (resp.body != null) {
               
                state.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
                System.out.println(state.writeBuf.limit());
                sc.write(state.writeBuf);
                if (state.writeBuf.hasRemaining())
                    return; // partial
                state.writeBuf = null;
                mainServer.closeClient(sc);
            }
            state.pending.poll();
            if (!resp.keepAlive) {
                mainServer.closeClient(sc);
                return;
            }
        } else {
            // streamed body (file)
            if (resp.fileChannel == null) {
                resp.fileChannel = FileChannel.open(resp.file.toPath(), StandardOpenOption.READ);
                resp.filePos = 0;
            }
            System.out.println(resp.file.toPath());
            long transferred = resp.fileChannel.transferTo(resp.filePos, 8192, sc);
            resp.filePos += transferred;
            
            if (resp.filePos >= resp.file.length()) {
                resp.fileChannel.close();
                state.pending.poll();
                if (!resp.keepAlive) {
                    mainServer.closeClient(sc);
                    return;
                }
            }
        }

    }

    private void handleRequest(SocketChannel sc, ClientState state) throws IOException {
        // sessions
        String sid = Cookie.getCookie(state.request.headers.getOrDefault("Cookie", ""), "SID");
        Session sess = null;
        if (sid != null)
            sess = sessions.get(sid);
        if (sess == null) {
            sid = UUID.randomUUID().toString();
            sess = new Session(sid);
            sessions.put(sid, sess);
        }

        HTTPResponse resp = router.route(state, sess, cgiHandler);
        // set session cookie
        resp.headers.put("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
        mainServer.enqueueResponse(sc, resp);
    }

    public List<Integer> getPorts() {
        return ports;
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

    public String getHost() {
        return host;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }
}
