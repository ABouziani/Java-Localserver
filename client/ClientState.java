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
    public boolean streamingActive = false;  // true = skip pipelining until body complete

    public SocketChannel ch;

    // Read buffer for socket reads (80KB)
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(80 * 1024);

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
    public long expectedBodyLength = -1;   // -1 = unknown or no body
    public long receivedBodyBytes = 0;
    public boolean bodyComplete = false;

    // Max accumulation size for small requests
    private static final int MAX_ACCUMULATION_SIZE = 100 * 1024 * 1024; // 100 MB (was 100 MB before, typo fix: 10*1024*1024*10 = 100 MB)

    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }

    /**
     * Append data from readBuf. If streamingToFile is true, write to temp file
     * and track progress. Else, accumulate in memory.
     */
    public void appendToAccumulation() throws IOException {
        readBuf.flip();

        if (streamingToFile) {
            int remainingBefore = readBuf.remaining();
            if (tempFileChannel != null && remainingBefore > 0) {
                tempFileChannel.write(readBuf);
            }
            int written = remainingBefore - readBuf.remaining();
            receivedBodyBytes += written;
            readBuf.clear();

            // Check if body is now complete
            if (expectedBodyLength > 0 && receivedBodyBytes >= expectedBodyLength) {
                bodyComplete = true;
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
        // No body expected (e.g., GET, or POST with no Content-Length — shouldn't happen due to validation)
        if (expectedBodyLength == -1 || expectedBodyLength == 0) {
            return true;
        }
        // Streaming or in-memory: complete when received >= expected
        return bodyComplete || (receivedBodyBytes >= expectedBodyLength);
    }

    /**
     * Reset body state (call before new request)
     */
    public void resetBodyState() {
        expectedBodyLength = -1;
        receivedBodyBytes = 0;
        bodyComplete = false;
        streamingToFile = false;
        streamingActive = false;  // ← add this line
    }
}
