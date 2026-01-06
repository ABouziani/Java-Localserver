package core;

import client.ClientState;
import configurations.Config;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
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

    try {
        int bytesRead = channel.read(state.readBuf);
        
        if (bytesRead == -1) {
            closeClient(channel);
            return;
        }
        
        if (bytesRead == 0) {
            return;
        }
        
        state.lastActive = System.currentTimeMillis();
        state.appendToAccumulation();
        
        VirtualServer server = null;
        boolean continueProcessing = true;
        
        while (continueProcessing && state.hasAccumulatedData()) {
            
            byte[] allBytes = state.allData.toByteArray();
            int offset = state.getConsumedBytes();
            int available = allBytes.length - offset;
            
            HTTPRequest req = state.getCurrentRequest();
            
            // STEP 1: Parse headers (ila ma parsinhomch deja)
            if (!state.areHeadersParsed()) {
                HTTPParser.ParseResult headerResult = HTTPParser.parseHeaders(allBytes, offset, available);
                
                if (!headerResult.complete) {
                    // Headers mazal incomplete
                    continueProcessing = false;
                    break;
                }
                
                req = headerResult.request;
                
                if (!req.valid) {
                    // Error f headers
                    sendErrorResponse(state, channel, req);
                    return;
                }
                
                // Headers parsed successfully!
                state.setCurrentRequest(req);
                state.setHeadersParsed(true);
                state.markConsumed(headerResult.bytesConsumed);
                
                System.out.println("DEBUG: Headers parsed, consumed " + headerResult.bytesConsumed + " bytes");
            }
            
            // STEP 2: Parse body (headers deja parsed)
            allBytes = state.allData.toByteArray();
            offset = state.getConsumedBytes();
            available = allBytes.length - offset;
            
            HTTPParser.ParseResult bodyResult = HTTPParser.parseBody(req, allBytes, offset, available);
            
            if (!bodyResult.complete) {
                // Body mazal incomplete
                continueProcessing = false;
                System.out.println("DEBUG: Body incomplete, waiting for more data...");
                break;
            }
            
            req = bodyResult.request;
            
            if (!req.valid) {
                // Error f body
                sendErrorResponse(state, channel, req);
                return;
            }
            
            // Mark consumed bytes (body)
            state.markConsumed(bodyResult.bytesConsumed);
            
            // Request kamel! (headers + body)
            System.out.println("DEBUG: Request complete! Consumed " + bodyResult.bytesConsumed + " bytes for body");
            state.setHeadersParsed(false); // Reset for next request
            
            if (server == null) {
                String host = req.headers.get("host");
                server = serverManager.getServer(host);
                
                if (server == null) {
                    System.err.println("Warning: No server found for host: " + host);
                    
                    HTTPResponse resp = new HTTPResponse();
                    resp.status = 404;
                    resp.keepAlive = false;
                    resp.body = "<html><body><h1>404 Not Found</h1></body></html>";
                    resp.headers.put("Content-Type", "text/html");
                    resp.headers.put("Content-Length", String.valueOf(resp.body.length()));
                    state.pending.add(resp);
                    channel.register(selector, SelectionKey.OP_WRITE);
                    state.clearAccumulation();
                    return;
                }
                
                state.setListenAddress(host);
            }
            
            server.read(req, state, channel);
            
            String connection = req.headers.getOrDefault("connection", "keep-alive").toLowerCase();
            if (connection.equals("close")) {
                continueProcessing = false;
            }
        }
        
        // Compact: remove consumed data
        state.compactAccumulation();
        state.resetAccumulationIfLarge();
        
        if (state.getAccumulationSize() > 1024 * 1024) {
            System.out.println("WARNING: Large accumulation buffer: " + 
                             state.getAccumulationSize() + " bytes for " + 
                             channel.getRemoteAddress());
        }
        
    } catch (RuntimeException e) {
        System.err.println("ERROR: Failed to process request: " + e.getMessage());
        e.printStackTrace();
        HTTPResponse resp = new HTTPResponse();        
        if (e.getMessage().contains("exceeds maximum")) {
            resp.status = 413;
            resp.body = "<html><body><h1>413 Payload Too Large</h1><p>Request size exceeds the maximum allowed size.</p></body></html>";
        } else {
            resp.status = 500;
            resp.body = "<html><body><h1>500 Internal Server Error</h1><p>An error occurred while processing your request.</p></body></html>";
        }
        resp.keepAlive = false;
        resp.headers.put("Content-Type", "text/html");
        resp.headers.put("Content-Length", String.valueOf(resp.body.length()));
        
        state.pending.add(resp);
        channel.register(selector, SelectionKey.OP_WRITE);
        state.clearAccumulation();
    } catch (IOException e) {
        System.err.println("ERROR: IO exception during read: " + e.getMessage());
        closeClient(channel);
    }
}
// Helper method
private void sendErrorResponse(ClientState state, SocketChannel channel, HTTPRequest req) throws IOException {
    HTTPResponse resp = new HTTPResponse();
    resp.status = req.errorCode;
    resp.keepAlive = false;
    resp.body = req.getErrorResponse();
    resp.headers.put("Content-Type", "text/html; charset=utf-8");
    resp.headers.put("Content-Length", String.valueOf(resp.body.getBytes(StandardCharsets.UTF_8).length));
    
    state.pending.add(resp);
    channel.register(selector, SelectionKey.OP_WRITE);
    state.clearAccumulation();
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