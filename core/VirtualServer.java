package core;

import cgi.CGIHandler;
import client.ClientState;
import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import routing.Route;
import routing.Router;
import session.Cookie;
import session.Session;
import session.SessionManager;

public class VirtualServer {

    private final ServerConfig config;
    private final MainServer mainServer;

    private final Router router;
    private final CGIHandler cgiHandler;
    private final SessionManager sessionManager;

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
        this.sessionManager = new SessionManager();
        this.errorPages = config.getErrorPages();
        this.routes = config.getRoutes();
        this.host = config.getHost();
        this.isDefault = config.getIsDefault();
        this.router = new Router(config);
        this.cgiHandler = new CGIHandler(config);
    }

    public void read(HTTPRequest req, ClientState state, SocketChannel sc) throws IOException {
        System.out.println("➡️ Dispatching request: " + req.path);
        // No body length check — handled by parser (Content-Length validation)

        try {
            handleRequest(sc, req, state);
        } catch (Throwable t) {
            t.printStackTrace();
            mainServer.enqueueResponse(sc, HTTPResponse.error(500, config));
        } finally {
            // Cleanup temp file if present (safe: MultipartForm only reads it)
            if (req.tempFilePath != null && Files.exists(req.tempFilePath)) {
                try {
                    Files.delete(req.tempFilePath);
                    System.out.println("DEBUG: Deleted temp file: " + req.tempFilePath);
                } catch (IOException e) {
                    System.err.println("WARN: Failed to delete temp file: " + req.tempFilePath);
                }
            }
        }
    }

    public void wwrite(SocketChannel sc, ClientState state) throws IOException {

        if (state.pending.isEmpty()) {
            return;
        }
        HTTPResponse resp = state.pending.peek();
        if (!resp.sentHeader) {
            byte[] h = resp.toHeaderBytes();
            state.writeBuf = ByteBuffer.wrap(h);
            resp.sentHeader = true;
        }
        if (state.writeBuf != null && state.writeBuf.hasRemaining()) {
            sc.write(state.writeBuf);
            if (state.writeBuf.hasRemaining()) {
                return; // partial

            }
            state.writeBuf = null;
        }
        if (!resp.streamed) {
            if (resp.body != null) {
                state.writeBuf = ByteBuffer.wrap(resp.body.getBytes(StandardCharsets.UTF_8));
                sc.write(state.writeBuf);
                if (state.writeBuf.hasRemaining()) {
                    return; // partial

                }
                state.writeBuf = null;
            }
            state.pending.poll();
            // if (!resp.keepAlive) {
            mainServer.closeClient(sc);
            // return;
            // }
        } else {
            // streamed body (file)
            if (resp.fileChannel == null) {
                resp.fileChannel = FileChannel.open(resp.file.toPath(), StandardOpenOption.READ);
                resp.filePos = 0;
            }
            //System.out.println(resp.file.toPath());
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

    public void write(SocketChannel sc, ClientState state) throws IOException {

        if (state.pending.isEmpty()) {
            return;
        }

        HTTPResponse resp = state.pending.peek();

        //WRITE HEADERS
        if (!resp.sentHeader) {
            if (state.writeBuf == null) {
                byte[] headerBytes = resp.toHeaderBytes();
                state.writeBuf = ByteBuffer.wrap(headerBytes);
            }

            sc.write(state.writeBuf);

            if (state.writeBuf.hasRemaining()) {
                // partial header write
                return;
            }

            // header fully written
            state.writeBuf = null;
            resp.sentHeader = true;
        }

        //WRITE BODY (NON-STREAMED)
        if (!resp.streamed) {
            if (resp.body != null) {
                if (state.writeBuf == null) {
                    state.writeBuf = ByteBuffer.wrap(
                            resp.body.getBytes(StandardCharsets.UTF_8)
                    );
                }

                sc.write(state.writeBuf);

                if (state.writeBuf.hasRemaining()) {
                    // partial body write
                    return;
                }

                // body fully written
                state.writeBuf = null;
            }

            // response fully sent
            state.pending.poll();

            if (!resp.keepAlive) {
                state.closeAfterWrite = true;
            }

            return;
        }

        //WRITE BODY (STREAMED FILE)
        if (resp.fileChannel == null) {
            resp.fileChannel = FileChannel.open(
                    resp.file.toPath(),
                    StandardOpenOption.READ
            );
            resp.filePos = 0;
        }

        long transferred = resp.fileChannel.transferTo(
                resp.filePos,
                8192,
                sc
        );

        if (transferred == 0) {
            // socket not ready for more data
            return;
        }

        resp.filePos += transferred;

        if (resp.filePos < resp.file.length()) {
            // file not finished yet
            return;
        }

        // file fully sent
        resp.fileChannel.close();
        resp.fileChannel = null;

        state.pending.poll();

        if (!resp.keepAlive) {
            state.closeAfterWrite = true;
        }

    }

    private void handleRequest(SocketChannel sc, HTTPRequest req, ClientState state) throws IOException {

        // sessions
        String sid = Cookie.getCookie(req.headers.getOrDefault("Cookie", ""), "SID");
        Session sess = null;
        if (sid != null) {
            sess = sessionManager.get(sid);
        }
        /*if ((req.method.equals("POST") || req.method.equals("DELETE")) && sid == null){
            mainServer.enqueueResponse(sc,HTTPResponse.error(403, config));
            return;
        }*/
        if (sess == null) {
            sess = sessionManager.create();
        }

        HTTPResponse resp = router.route(req, sess, cgiHandler);
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
