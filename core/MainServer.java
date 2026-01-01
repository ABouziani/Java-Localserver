package core;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import client.ClientState;
import configurations.Config;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;

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
        clientChannel.configureBlocking(false);
        // get add (port) from socket channal and set it in client state 
        // String listenAddress = (String) key.attachment();
        ClientState state = new ClientState(clientChannel);
        clients.put(clientChannel, state);

        clientChannel.register(selector, SelectionKey.OP_READ, state);
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
            HTTPRequest req = HTTPParser.tryParse(state.in.toString());
            if (req == null)
                break;
            state.setCurrentRequest(req);
            state.in = new StringBuilder(req.remain);
            if (server == null){
                String host = req.headers.get("host");
                server = serverManager.getServer(host);
                
                if (server == null){
                    state.setListenAddress(host);
                    System.err.println("Warning: No server found for address " + host + 
                                 "");
                    break;
                }
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

    // public void enqueueResponse(SocketChannel sc, HTTPResponse resp, ClientState state) {
    //     state.setResponse(resp);

    //     // Trigger write by updating interest ops
    //     SelectionKey key = sc.keyFor(selector);
    //     if (key != null && key.isValid()) {
    //         key.interestOps(SelectionKey.OP_WRITE);
    //         selector.wakeup(); // Wake up selector if it's blocked
    //     }
    // }
    // private void closeConnection(SocketChannel channel, SelectionKey key) {
    //     try {
    //         clients.remove(channel);
    //         key.cancel();
    //         channel.close();
    //     } catch (IOException e) {
    //         // Log error
    //     }
    // }
     // private String extractHostFromRequest(ClientState state) {
    //     HTTPRequest req = state.getCurrentRequest();
        
    //     if (req == null) {
    //         return null;
    //     }
    //     String host = req.headers.getOrDefault("Host", "");
    //     if (host.contains(":")) {
    //         host = host.substring(0, host.indexOf(":"));
    //     }
    //     return host.toLowerCase().trim();
    // }
    
    
    
}