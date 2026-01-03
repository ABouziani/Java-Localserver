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

        // Parse port from socketChannel to determine which VirtualServer to use
        int r = channel.read(state.readBuf);
        if (r == -1) {
            closeClient(channel);
            return;
        }
        state.lastActive = System.currentTimeMillis();
        state.readBuf.flip();
        byte[] b = new byte[state.readBuf.remaining()];
        state.readBuf.get(b);
        state.readBuf.clear();
        state.in.append(new String(b, StandardCharsets.UTF_8));
        VirtualServer server = null;
        while (true) {
            byte[] remaining = state.in.toString().getBytes(StandardCharsets.UTF_8);
            HTTPRequest req = HTTPParser.tryParse(remaining);
            if (req == null)
                break;
            if (req.valid == false) {
                HTTPResponse resp = new HTTPResponse();
                resp.keepAlive = false;
                String errorHtml = req.getErrorResponse();

                resp.body = errorHtml.getBytes(StandardCharsets.UTF_8);
                resp.headers.put("Content-Type", "text/html; charset=utf-8");
                resp.headers.put("Content-Length", Integer.toString(resp.body.length));
                state.pending.add(resp);
                channel.register(selector, SelectionKey.OP_WRITE);
                return;
            }
            state.setCurrentRequest(req);
            state.in = new StringBuilder(req.remain.toString());
            if (server == null) {
                String host = req.headers.get("host");
                server = serverManager.getServer(host);
                if (server == null) {
                    System.err.println("Warning: No server found for address " + host +
                            "");
                    break;
                }
                state.setListenAddress(host);
                state.setListenAddress(host);
            }
            server.read(req, state, channel);
        }

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