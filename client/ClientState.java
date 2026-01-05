package client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Queue;

import http.HTTPRequest;
import http.HTTPResponse;

public class ClientState {

    public SocketChannel ch;

    // Read buffer for socket reads (8KB)
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

    // File streaming for multipart
    public Path tempFilePath;
    public FileChannel tempFileChannel;
    public boolean streamingToFile = false;

    // Max accumulation size for small requests
    private static final int MAX_ACCUMULATION_SIZE = 10 * 1024 * 1024 * 10; // 10 MB

    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }

    /**
     * Append data from readBuf
     * If streamingToFile is true, write directly to temp file
     * Else, use accumulationBuf
     */
    public void appendToAccumulation() throws IOException {
        readBuf.flip();

        if (streamingToFile) {
            // write all bytes to temp file
            if (tempFileChannel != null && readBuf.hasRemaining()) {
                tempFileChannel.write(readBuf);
            }
            readBuf.clear();
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
        if (newCapacity > MAX_ACCUMULATION_SIZE) newCapacity = MAX_ACCUMULATION_SIZE;

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
     * Reset accumulation buffer if too large
     */
    public void resetAccumulationIfLarge() {
        if (accumulationBuf.capacity() > 100 * 1024 && accumulationBuf.position() == 0) {
            accumulationBuf = ByteBuffer.allocate(16 * 1024);
            System.out.println("DEBUG: Reset accumulation buffer to initial size");
        }
    }

    // Getters / Setters
    public HTTPRequest getCurrentRequest() { return currentRequest; }
    public void setCurrentRequest(HTTPRequest c) { currentRequest = c; }
    public String getListenAddress() { return listenAddress; }
    public void setListenAddress(String l) { listenAddress = l; }

    /**
     * Start streaming a multipart body to a temp file
     */
    public void startStreamingToFile() throws IOException {
        if (!streamingToFile) {
            tempFilePath = Files.createTempFile("upload-", ".tmp");
            tempFileChannel = FileChannel.open(tempFilePath, StandardOpenOption.WRITE);
            streamingToFile = true;
            System.out.println("DEBUG: Streaming multipart upload to temp file: " + tempFilePath);
        }
    }

    /**
     * Finish streaming and close temp file
     */
    public void finishStreaming() throws IOException {
        if (streamingToFile) {
            if (tempFileChannel != null && tempFileChannel.isOpen()) {
                tempFileChannel.close();
            }
            streamingToFile = false;
        }
    }

    /**
     * Delete temp file if needed
     */
    public void deleteTempFile() throws IOException {
        if (tempFilePath != null && Files.exists(tempFilePath)) {
            Files.delete(tempFilePath);
            tempFilePath = null;
        }
    }
}
