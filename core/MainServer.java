package core;

import client.ClientState;
import configurations.Config;
import http.HTTPParser;
import http.HTTPRequest;
import http.HTTPResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
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
    private static final long SELECT_TIMEOUT_MS = 100;
    private static final long IDLE_TIMEOUT_MS = 40000; // headers / keep-alive
    private static final long BODY_TIMEOUT_MS = 40000;

    public MainServer(Config config) throws IOException {
        this.selector = Selector.open();
        this.serverManager = new ServerManager(config, this);
    }

    public void start() throws IOException {
        serverManager.bindAllListeners(selector);

        // Main event loop
        while (true) {
            selector.select(SELECT_TIMEOUT_MS);
            checkTimeouts(System.currentTimeMillis());

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) {
                    continue;
                }

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
        if (clientChannel == null) {
            return;
        }

        try {
            clientChannel.configureBlocking(false);
            clientChannel.socket().setTcpNoDelay(true);
            int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            ClientState state = new ClientState(clientChannel,port);
            state.lastActive = System.currentTimeMillis();
            clients.put(clientChannel, state);

            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            clientKey.attach(state);
        } catch (IOException e) {
            e.printStackTrace();
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
                closeClient(channel);
                return;
            }

            state.lastActive = System.currentTimeMillis();

            // Append new data to accumulation or file
            state.appendToAccumulation();

            // If we're actively streaming a body, wait for completion
            if (state.streamingActive) {
                if (state.isBodyComplete()) {
                    // Body fully received — finalize and dispatch
                    try {
                        state.finishStreaming();

                        HTTPRequest req = state.getCurrentRequest();
                        if (req != null) {
                            // Attach temp file to request for handler
                            req.tempFilePath = state.tempFilePath;
                            // Lookup server (we stored listenAddress earlier, or re-parse Host)
                            VirtualServer server = null;
                            String host = req.headers.get("host");
                            if (host != null) {
                                server = serverManager.getServer(host);
                            }

                            if (server == null) {
                                System.err.println("Warning: No server found for host: " + host);
                                sendErrorResponse(channel, state, 404,
                                        "<html><body><h1>404 Not Found</h1></body></html>");
                                return;
                            }
                            if (!server.getIsDefault() && !server.getPorts().contains(state.portActive)){
                                System.err.println("Warning: The requested port " + state.portActive + " is not in the server's configured ports.");
                                sendErrorResponse(channel, state, 400,
                                    "<html><body><h1>400 Bad Request</h1>" +
                                    "<p>Invalid port: " + state.portActive + "</p>" +
                                    "</body></html>");
                                return;
                            }
                            // Dispatch to server — handler can read req.tempFilePath
                            server.read(req, state, channel);

                            // Reset for next request
                            state.resetBodyState();
                            state.clearAccumulation(); // clear any leftover (should be none)
                        }
                    } catch (Exception e) {
                        System.err.println("ERROR: Failed to finalize streamed request: " + e.getMessage());
                        sendErrorResponse(channel, state, 500,
                                "<html><body><h1>500 Internal Server Error</h1></body></html>");
                    }
                }
                // Still receiving body — do nothing else this cycle
                return;
            }

            // Normal parsing (no active streaming)
            ByteBuffer parseBuf = state.getAccumulationForParsing();
            VirtualServer server = null;

            HTTPRequest req = HTTPParser.tryParse(parseBuf);

            if (req == null) {
                // Incomplete headers — wait for more data
                return;
            }

            if (!req.valid) {
                sendErrorResponse(channel, state, req.errorCode, req.getErrorResponse());
                return;
            }

            // Store request
            state.setCurrentRequest(req);

            // Lookup server (first request in connection)
            if (server == null) {
                String host = req.headers.get("host");
                server = serverManager.getServer(host);
                if (server == null) {
                    System.err.println("Warning: No server found for host: " + host);
                    sendErrorResponse(channel, state, 404,
                            "<html><body><h1>404 Not Found</h1></body></html>");
                    return;
                }
                if (!server.getIsDefault() && !server.getPorts().contains(state.portActive)){
                    System.err.println("Warning: The requested port " + state.portActive + " is not in the server's configured ports.");
                    sendErrorResponse(channel, state, 400,
                        "<html><body><h1>400 Bad Request</h1>" +
                        "<p>Invalid port: " + state.portActive + "</p>" +
                        "</body></html>");
                    return;
                }
                state.setListenAddress(host);
            }
            if (req.expectedContentLength > server.getClientMaxBody()) {
                sendErrorResponse(channel, state, 413,
                        "Content-Length exceeds maximum allowed size");
                return;
            }
            // Check if streaming is needed
            if (req.needsStreaming) {
                // ✅ START streaming

                state.resetBodyState();
                state.expectedBodyLength = req.expectedContentLength;
                state.isChunkedTransfer = req.isChunked; // ← Set flag for chunked detection
                state.startStreamingToFile(); // ← CRITICAL: was missing!
                state.streamingActive = true;

                // Drain any remaining body bytes from parseBuf (headers already consumed)
                if (parseBuf.hasRemaining()) {
                    int remaining = parseBuf.remaining();

                    // Capture the bytes before they're consumed
                    byte[] drainedData = new byte[remaining];
                    parseBuf.get(drainedData);

                    if (state.tempFileChannel != null) {
                        state.tempFileChannel.write(ByteBuffer.wrap(drainedData));
                    }
                    state.receivedBodyBytes += remaining;

                    // IMPORTANT: For chunked transfers, check if terminating chunk is already in
                    // this data
                    if (req.isChunked) {
                        state.detectChunkedEnd(drainedData); // ← Check if body is complete NOW
                    }
                }

                state.compactAccumulation();

                // Check if we're already done (e.g., tiny multipart body)
                if (state.isBodyComplete()) {
                    state.streamingActive = false;
                    state.finishStreaming();
                    req.tempFilePath = state.tempFilePath; // now non-null
                    server.read(req, state, channel);
                    state.resetBodyState();
                    return;
                }

                // Suspend pipelining — wait for more body data
                return;
            }

            // Small request — dispatch immediately
            server.read(req, state, channel);

            state.compactAccumulation();
            state.resetAccumulationIfLarge();

        } catch (RuntimeException e) {
            System.err.println("ERROR: Failed to process request: " + e.getMessage());
            String body = e.getMessage().contains("exceeds maximum")
                    ? "<html><body><h1>413 Payload Too Large</h1><p>Request size exceeds the maximum allowed.</p></body></html>"
                    : "<html><body><h1>500 Internal Server Error</h1><p>An error occurred.</p></body></html>";
            sendErrorResponse(channel, state, e.getMessage().contains("exceeds maximum") ? 413 : 500, body);
        } catch (IOException e) {
            if (e instanceof java.net.SocketException && e.getMessage().contains("Connection reset")) {
                // expected under stress, ignore or log minimally
                System.out.println("Client closed connection prematurely (expected under stress).");
            } 
            closeClient(channel);
        }
    }

    // Helper to avoid duplication
    private void sendErrorResponse(SocketChannel channel, ClientState state, int status, String body)
            throws IOException {
        HTTPResponse resp = new HTTPResponse();
        resp.status = status;
        resp.keepAlive = false;
        resp.body = body;
        resp.headers.put("Content-Type", "text/html; charset=utf-8");
        resp.headers.put("Content-Length", String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));

        state.pending.add(resp);
        //channel.register(selector, SelectionKey.OP_WRITE);
        SelectionKey key = channel.keyFor(selector);
        if (key != null && key.isValid()) {
            key.interestOps(SelectionKey.OP_WRITE);
        }
        state.clearAccumulation();
        state.resetBodyState();
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = clients.get(channel);
        try {

            if (state == null) {
                closeClient(channel);
                return;
            }
            if (state.pending.isEmpty()) {
                SelectionKey key2 = channel.keyFor(selector);
                if (key2 != null && key2.isValid()) {
                    key2.interestOps(SelectionKey.OP_READ);
                }
                return;
            }
            // if more pending, keep OP_WRITE
            if (!state.pending.isEmpty()) {
                SelectionKey key2 = channel.keyFor(selector);
                if (key2 != null && key2.isValid()) {
                    key2.interestOps(SelectionKey.OP_WRITE);
                }
            } else {
                SelectionKey key2 = channel.keyFor(selector);
                if (key2 != null && key2.isValid()) {
                    key2.interestOps(SelectionKey.OP_READ);
                }
            }

            String listenAddress = state.getListenAddress();
            VirtualServer server = serverManager.getServer(listenAddress);
            server.write(channel, state);
            if (state != null && state.closeAfterWrite) {
                key.cancel();
                closeClient((SocketChannel) key.channel());
                return;
            }
        } catch (IOException e) {
            if (e instanceof java.net.SocketException && e.getMessage().contains("Connection reset")) {
                // expected under stress, ignore or log minimally
                System.out.println("Client closed connection prematurely (expected under stress).");
            } 
            closeClient(channel);
        }
    }

    public void enqueueResponse(SocketChannel sc, HTTPResponse resp) throws IOException {
        ClientState st = clients.get(sc);
        if (st == null) {
            closeClient(sc);
            return;
        }
        st.pending.add(resp);
        SelectionKey key = sc.keyFor(selector);
        if (key != null && key.isValid()) {
            key.interestOps(SelectionKey.OP_WRITE);
        }

        //sc.register(selector, SelectionKey.OP_WRITE);
    }

    public void xcloseClient(SocketChannel sc) throws IOException {
        clients.remove(sc);
        sc.close();
    }

    public void closeClient(SocketChannel sc) {
        try {
            if (sc == null) {
                clients.remove(sc);
                return;
            } 
            SelectionKey key = sc.keyFor(selector);
            if (key != null) {
                key.cancel();
            }
            clients.remove(sc);
            sc.close();
        } catch (IOException ignored) {
        }
    }

    private void checkTimeouts(long now) {
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) {
                continue;
            }
            if (!(key.channel() instanceof SocketChannel)) {
                continue;
            }

            SocketChannel channel = (SocketChannel) key.channel();
            ClientState state = clients.get(channel);
            if (state == null) {
                continue;
            }

            long idleTime = now - state.lastActive;

            long timeout = state.streamingActive
                    ? BODY_TIMEOUT_MS
                    : IDLE_TIMEOUT_MS;

            if (idleTime > timeout) {
                System.out.println("Timing out -> Chanel closed");
                try {
                    key.cancel();
                    closeClient(channel);
                } catch (Exception ignored) {
                }
            }
        }
    }

}
