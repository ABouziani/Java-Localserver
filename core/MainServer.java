package core;

import client.ClientState;
import configurations.Config;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MainServer {
    private final Selector selector;
    private final ServerManager serverManager;
    private final Map<SocketChannel, ClientState> clients = new HashMap<>();

    public MainServer(Config config) throws IOException {
        this.selector = Selector.open();
        this.serverManager = new ServerManager(config, this);
    }

    public void start() throws IOException {
        serverManager.bindAllListeners(selector);

        // Main event loop
        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid())
                    continue;

                if (key.isAcceptable()) {
                    handleAccept(key);
                } else if (key.isReadable()) {
                    handleRead(key);
                } else if (key.isWritable()) {
                    handleWrite(key);
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null)
            return;

        try {
            clientChannel.configureBlocking(false);

            // Optional: configure socket options
            // clientChannel.socket().setTcpNoDelay(true);
            // clientChannel.socket().setKeepAlive(true);

            ClientState state = new ClientState(clientChannel);
            clients.put(clientChannel, state);

            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            clientKey.attach(state);
        } catch (IOException e) {
            closeClient(clientChannel);
            throw e;
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = clients.get(channel);
        if (System.currentTimeMillis() - state.lastActive >= 20000) {
            // handle on the write also
            closeClient(channel);
            return;
        }
        // Parse port from socketChannel to determine which VirtualServer to use
        int r = channel.read(state.readBuf);
        System.out.println("----------------000000");
        if (r == -1) {
            closeClient(channel);
            return;
        }
        state.lastActive = System.currentTimeMillis();
        state.readBuf.flip();
        byte[] chunk = new byte[state.readBuf.remaining()];
        state.readBuf.get(chunk);
        
        // state.in.append(new String(b, StandardCharsets.UTF_8));
        // byte[] newData = new byte[state.in.length + chunk.length];
        // System.arraycopy(state.in, 0, newData, 0, state.in.length);
        // System.arraycopy(chunk, 0, newData, state.in.length, chunk.length);
        
        state.readBuf.clear();
        state.in.write(state.readBuf.array());

        if (!state.headersParsed) {
            String headrsString = state.getHeaderString();
            if (headrsString == null)
                return;
            HTTPRequest req = HTTPParser.ParseHeaders(headrsString);
            if (req == null)return;
            if (req.valid == false) {
                HTTPResponse resp = new HTTPResponse();
                resp.keepAlive = false;
                System.out.print(state.request.errorCode);
                resp.body = state.request.getErrorResponse();
                resp.headers.put("Content-Type", "text/html; charset=utf-8");
                resp.headers.put("Content-Length", Integer.toString(resp.body.getBytes().length));
                state.pending.add(resp);
                channel.register(selector, SelectionKey.OP_WRITE);
                return;
            }

            state.setCurrentRequest(req);
            state.headersParsed = true;
            if (req.method.equals("POST")) {
                String te = state.request.headers.get("transfer-encoding");
                String cl = state.request.headers.get("content-length");
    
                if ("chunked".equalsIgnoreCase(te)) {
                    state.chunked = true;
                    state.openTempFile();
                } else if (cl != null) {
                    state.expectedLength = Long.parseLong(cl);
                    state.openTempFile();
                }
            }
        }

        VirtualServer server = null;
        // while (true) {
        // System.out.println("----------------111111111");
        // if (req == null)
        // break;
        // System.out.println("----------------2222222222");
        if (state.request.valid == false) {
            HTTPResponse resp = new HTTPResponse();
            resp.keepAlive = false;
            System.out.print(state.request.errorCode);
            resp.body = state.request.getErrorResponse();
            resp.headers.put("Content-Type", "text/html; charset=utf-8");
            resp.headers.put("Content-Length", Integer.toString(resp.body.getBytes().length));
            state.pending.add(resp);
            channel.register(selector, SelectionKey.OP_WRITE);
            return;
        }
        // state.setCurrentRequest(req);
        // state.in = new StringBuilder(state.request.remain);
        if (server == null) {
            String host = state.request.headers.get("host");
            server = serverManager.getServer(host);
            if (server == null) {
                System.err.println("Warning: No server found for address " + host +
                        "");
                closeClient(channel);
                return;
            }
            state.setListenAddress(host);
        }
        // if (state.chunked)
        //     state.handleChunkedBody();
        // else if (state.expectedLength >= 0)
        //     state.handleContentLengthBody();

        // when finished
        // if (state.expectedLength == state.receivedLength && !state.chunked) {
        //     System.out.println("Upload saved to " + state.tempFile);
        // }
        HTTPRequest req = HTTPParser.complateRequest(state);
        if (req == null) return;
        server.read(state, channel);
        // }

    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = clients.get(channel);
        if (state == null) {
            closeClient(channel);
            return;
        }
        if (state.pending.isEmpty()) {
            channel.register(selector, SelectionKey.OP_READ);
            return;
        }
        // if more pending, keep OP_WRITE
        if (!state.pending.isEmpty())
            channel.register(selector, SelectionKey.OP_WRITE);
        else
            channel.register(selector, SelectionKey.OP_READ);

        String listenAddress = state.getListenAddress();
        VirtualServer server = serverManager.getServer(listenAddress);
        server.write(channel, state);
    }

    public void enqueueResponse(SocketChannel sc, HTTPResponse resp) throws IOException {
        ClientState st = clients.get(sc);
        if (st == null)
            return;
        st.pending.add(resp);
        sc.register(selector, SelectionKey.OP_WRITE);
    }

    public void closeClient(SocketChannel sc) throws IOException {
        clients.remove(sc);
        sc.close();
    }
}