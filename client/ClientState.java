package client;

import http.HTTPRequest;
import http.HTTPResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Queue;

public class ClientState {

    // Parsing control — NEW
    public boolean streamingActive = false; // true = skip pipelining until body complete

    public SocketChannel ch;

    // Read buffer for socket reads (80KB)
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(1024 * 8);

    // Accumulation buffer for small requests (headers / JSON)
    public ByteBuffer accumulationBuf = ByteBuffer.allocate(16 * 1024); // 16KB

    // Write buffer
    public ByteBuffer writeBuf = null;

    // Response queue
    public Queue<HTTPResponse> pending = new ArrayDeque<>();

    // Connection state
    public long lastActive = System.currentTimeMillis();
    public boolean closed = false;

    // Current request
    private HTTPRequest currentRequest;
    private String listenAddress;

    // File streaming for large/multipart bodies
    public Path tempFilePath;
    public FileChannel tempFileChannel;
    public boolean streamingToFile = false;

    // Body reception tracking — NEW
    public long expectedBodyLength = -1; // -1 = unknown or no body
    public long receivedBodyBytes = 0;
    public boolean bodyComplete = false;
    public boolean isChunkedTransfer = false; // ← Track if this is specifically a chunked transfer

    // Max accumulation size for small requests
    private static final int MAX_ACCUMULATION_SIZE = 100 * 1024 * 1024; // 100 MB (was 100 MB before, typo fix:
                                                                        // 10*1024*1024*10 = 100 MB)

    // Chunked decoding state
    private byte[] lastBytes = new byte[5]; // Track last 5 bytes to detect 0\r\n\r\n
    private int lastBytesPos = 0;
    private boolean atLineStart = true; // Track if we're at the start of a line

    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }

    public void appendToAccumulation() throws IOException {
        readBuf.flip();

        if (streamingToFile) {
            int remainingBefore = readBuf.remaining();
            byte[] data = new byte[remainingBefore];
            readBuf.get(data);

            if (tempFileChannel != null && data.length > 0) {
                tempFileChannel.write(ByteBuffer.wrap(data));
            }
            receivedBodyBytes += data.length;
            readBuf.clear();

            // Check if body is now complete
            if (expectedBodyLength > 0 && receivedBodyBytes >= expectedBodyLength) {
                bodyComplete = true;
            }

            // For chunked (expectedBodyLength == -1): detect terminating chunk (0\r\n\r\n)
            if (isChunkedTransfer) {
                detectChunkedEnd(data);
            }
            return;
        }

        // Normal accumulation (small requests)
        if (accumulationBuf.remaining() < readBuf.remaining()) {
            int needed = accumulationBuf.position() + readBuf.remaining();
            if (needed > MAX_ACCUMULATION_SIZE) {
                throw new RuntimeException("Request size exceeds maximum allowed: " + MAX_ACCUMULATION_SIZE);
            }
            growAccumulationBuffer(readBuf.remaining());
        }

        accumulationBuf.put(readBuf);
        readBuf.clear();
    }

    /**
     * Grow accumulation buffer
     */
    private void growAccumulationBuffer(int additionalSpace) {
        int currentSize = accumulationBuf.position();
        int currentCapacity = accumulationBuf.capacity();
        int neededCapacity = currentSize + additionalSpace;

        int newCapacity = Math.max(currentCapacity * 2, neededCapacity);
        if (newCapacity > MAX_ACCUMULATION_SIZE) {
            newCapacity = MAX_ACCUMULATION_SIZE;
        }

        ByteBuffer newBuf = ByteBuffer.allocate(newCapacity);
        accumulationBuf.flip();
        newBuf.put(accumulationBuf);
        accumulationBuf = newBuf;

        System.out.println("DEBUG: Accumulation buffer grown to " + newCapacity + " bytes");
    }

    /**
     * Flip accumulationBuf for parsing
     */
    public ByteBuffer getAccumulationForParsing() {
        accumulationBuf.flip();
        return accumulationBuf;
    }

    /**
     * Compact accumulationBuf after parsing
     */
    public void compactAccumulation() {
        accumulationBuf.compact();
    }

    /**
     * Clear accumulationBuf
     */
    public void clearAccumulation() {
        accumulationBuf.clear();
    }

    /**
     * Get current accumulation size
     */
    public int getAccumulationSize() {
        return accumulationBuf.position();
    }

    /**
     * Reset accumulation buffer if idle and oversized
     */
    public void resetAccumulationIfLarge() {
        if (accumulationBuf.capacity() > 100 * 1024 && accumulationBuf.position() == 0) {
            accumulationBuf = ByteBuffer.allocate(16 * 1024);
            System.out.println("DEBUG: Reset accumulation buffer to initial size");
        }
    }

    // Getters / Setters
    public HTTPRequest getCurrentRequest() {
        return currentRequest;
    }

    public void setCurrentRequest(HTTPRequest c) {
        currentRequest = c;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String l) {
        listenAddress = l;
    }

    /**
     * Start streaming to temp file
     */
    public void startStreamingToFile() throws IOException {

        if (!streamingToFile) {
            tempFilePath = Files.createTempFile("upload-", ".tmp");
            tempFileChannel = FileChannel.open(tempFilePath, StandardOpenOption.WRITE);
            streamingToFile = true;
            receivedBodyBytes = 0;
            bodyComplete = false;
            System.out.println("DEBUG: Streaming POST body to temp file: " + tempFilePath);
            System.out.println("DEBUG: Created temp file: " + tempFilePath);
        }
    }

    /**
     * Finish streaming: close file, mark complete
     */
    public void finishStreaming() throws IOException {
        if (streamingToFile) {
            if (tempFileChannel != null && tempFileChannel.isOpen()) {
                tempFileChannel.close();
            }
            streamingToFile = false;
            bodyComplete = true;
            System.out.println("DEBUG: Finished streaming. Total bytes: " + receivedBodyBytes);
        }
    }

    /**
     * Delete temp file if it exists
     */
    public void deleteTempFile() throws IOException {
        if (tempFilePath != null && Files.exists(tempFilePath)) {
            Files.delete(tempFilePath);
            tempFilePath = null;
            System.out.println("DEBUG: Deleted temp file");
        }
    }

    /**
     * Check if request body is fully received
     */
    public boolean isBodyComplete() {
        // No body expected (e.g., GET)
        if (expectedBodyLength == 0) {
            return true;
        }
        // For unknown length (chunked): wait for streaming to finish
        if (expectedBodyLength == -1) {
            return bodyComplete;
        }
        // For known length: complete when received >= expected
        return bodyComplete || (receivedBodyBytes >= expectedBodyLength);
    }

    /**
     * Detect if chunked transfer has ended by looking for 0\r\n\r\n marker
     * Only matches at line boundaries to avoid false positives in text data
     */
    public void detectChunkedEnd(byte[] data) {
        // Look for pattern: 0\r\n\r\n (only at line start)
        final byte[] CHUNKED_END = { '0', '\r', '\n', '\r', '\n' };

        for (byte b : data) {
            // Shift buffer
            for (int i = 0; i < lastBytes.length - 1; i++) {
                lastBytes[i] = lastBytes[i + 1];
            }
            lastBytes[lastBytes.length - 1] = b;
            lastBytesPos = Math.min(lastBytesPos + 1, lastBytes.length);

            // Track line boundaries: we're at line start after \r\n
            if (lastBytesPos >= 2) {
                if (lastBytes[lastBytes.length - 2] == '\r' && lastBytes[lastBytes.length - 1] == '\n') {
                    atLineStart = true;
                }
            }

            // Check if last 5 bytes match terminating chunk, but only if '0' is at line
            // start
            if (lastBytesPos >= 5 && atLineStart) {
                boolean matches = true;
                for (int i = 0; i < 5; i++) {
                    if (lastBytes[i] != CHUNKED_END[i]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    bodyComplete = true;
                    System.out.println("✅ CHUNKED END DETECTED at byte " + receivedBodyBytes);
                    return;
                }
            }

            // If we read a non-whitespace byte and we're not building the pattern, we're
            // not at line start
            if (lastBytesPos >= 1 && b != '\r' && b != '\n' && atLineStart) {
                // Check if this byte is NOT the start of '0\r\n\r\n'
                if (b != '0') {
                    atLineStart = false;
                }
            }
        }
    }

    /**
     * Reset body state (call before new request)
     */
    public void resetBodyState() {
        expectedBodyLength = -1;
        receivedBodyBytes = 0;
        bodyComplete = false;
        streamingToFile = false;
        streamingActive = false;
        isChunkedTransfer = false; // ← Reset chunked flag
        lastBytesPos = 0; // Reset chunked detection
        atLineStart = true; // Reset line boundary tracking
    }
}
