package core;

import client.ClientState;
import configurations.Config;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;

import java.io.File;
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
        
        // Ila kaynin streaming body, nkatiwo directly f file
        if (state.isStreamingBody()) {
            state.readBuf.flip();
            byte[] data = new byte[state.readBuf.remaining()];
            state.readBuf.get(data);
            state.readBuf.clear();
            
            state.writeToBodyFile(data, 0, data.length);
            
            // Check ila kamel body
            if (state.isBodyStreamingComplete()) {
                HTTPRequest req = state.getCurrentRequest();
                File bodyFile = state.finishBodyStreaming();
                
                // Nzido file path l request
                req.bodyFile = bodyFile;
                req.bodyBytes = null; // Ma khassnahch f RAM
                
                System.out.println("DEBUG: Body streaming complete!");
                state.setHeadersParsed(false);
                
                // Process request
                String host = req.headers.get("host");
                VirtualServer server = serverManager.getServer(host);
                
                if (server == null) {
                    System.err.println("Warning: No server found for host: " + host);
                    sendErrorResponse(state, channel, HTTPRequest.invalid(404, "Not Found"));
                    return;
                }
                
                state.setListenAddress(host);
                server.read(req, state, channel);
                
                String connection = req.headers.getOrDefault("connection", "keep-alive").toLowerCase();
                if (connection.equals("close")) {
                    closeClient(channel);
                    return;
                }
            }
            
            return;
        }
        
        // Normal flow: append to accumulation (headers)
        state.appendToAccumulation();
        
        VirtualServer server = null;
        boolean continueProcessing = true;
        
        while (continueProcessing && state.hasAccumulatedData()) {
            
            byte[] allBytes = state.allData.toByteArray();
            int offset = state.getConsumedBytes();
            int available = allBytes.length - offset;
            
            HTTPRequest req = state.getCurrentRequest();
            
            // STEP 1: Parse headers
            if (!state.areHeadersParsed()) {
                HTTPParser.ParseResult headerResult = HTTPParser.parseHeaders(allBytes, offset, available);
                
                if (!headerResult.complete) {
                    continueProcessing = false;
                    break;
                }
                
                req = headerResult.request;
                
                if (!req.valid) {
                    sendErrorResponse(state, channel, req);
                    return;
                }
                
                state.setCurrentRequest(req);
                state.setHeadersParsed(true);
                state.markConsumed(headerResult.bytesConsumed);
                
                System.out.println("DEBUG: Headers parsed");
                
                // Check ila khassna n-stream body
                String method = req.method;
                if (method.equals("POST")) {
                    long contentLength = -1;
                    boolean isChunked = false;
                    
                    if (req.headers.containsKey("transfer-encoding")) {
                        String te = req.headers.get("transfer-encoding").toLowerCase();
                        if (te.contains("chunked")) {
                            isChunked = true;
                        }
                    }
                    
                    if (req.headers.containsKey("content-length")) {
                        try {
                            contentLength = Long.parseLong(req.headers.get("content-length").trim());
                        } catch (NumberFormatException e) {
                            sendErrorResponse(state, channel, HTTPRequest.invalid(400, "Invalid Content-Length"));
                            return;
                        }
                    }
                    
                    // Ila body kbir (> 1MB), n-streamer f file
                    if (contentLength > 1024 * 1024 && !isChunked) {
                        state.startBodyStreaming(contentLength);
                        
                        // Nchofو ila kayna data deja f accumulation buffer
                        allBytes = state.allData.toByteArray();
                        offset = state.getConsumedBytes();
                        available = allBytes.length - offset;
                        
                        if (available > 0) {
                            // Nkatiwo data li kayna deja
                            int toWrite = (int) Math.min(available, contentLength);
                            state.writeToBodyFile(allBytes, offset, toWrite);
                            state.markConsumed(toWrite);
                        }
                        
                        // Nnaqsو accumulation buffer
                        state.compactAccumulation();
                        
                        System.out.println("DEBUG: Started streaming body to file (size: " + contentLength + ")");
                        return; // Nkamlo f next read
                    }
                }
            }
            
            // STEP 2: Parse body (small bodies only, < 1MB)
            allBytes = state.allData.toByteArray();
            offset = state.getConsumedBytes();
            available = allBytes.length - offset;
            
            HTTPParser.ParseResult bodyResult = HTTPParser.parseBody(req, allBytes, offset, available);
            
            if (!bodyResult.complete) {
                continueProcessing = false;
                System.out.println("DEBUG: Body incomplete, waiting...");
                break;
            }
            
            req = bodyResult.request;
            
            if (!req.valid) {
                sendErrorResponse(state, channel, req);
                return;
            }
            
            state.markConsumed(bodyResult.bytesConsumed);
            
            System.out.println("DEBUG: Request complete!");
            state.setHeadersParsed(false);
            
            if (server == null) {
                String host = req.headers.get("host");
                server = serverManager.getServer(host);
                
                if (server == null) {
                    System.err.println("Warning: No server found for host: " + host);
                    sendErrorResponse(state, channel, HTTPRequest.invalid(404, "Not Found"));
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
        
        state.compactAccumulation();
        state.resetAccumulationIfLarge();
        
    } catch (RuntimeException e) {
        System.err.println("ERROR: Failed to process request: " + e.getMessage());
        e.printStackTrace();
        sendErrorResponse(state, channel, HTTPRequest.invalid(500, "Internal Server Error"));
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