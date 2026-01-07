package client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;

import http.HTTPRequest;
import http.HTTPResponse;

public class ClientState {
    public SocketChannel ch;
    
    // Read buffer for socket reads (fixed size 8KB)
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(8 * 1024);
    
    // Accumulation for incoming data (headers only, not body)
    public ByteArrayOutputStream allData = new ByteArrayOutputStream();
    
    // Write buffer
    public ByteBuffer writeBuf = null;
    
    // Legacy string buffer (deprecated)
    @Deprecated
    public StringBuilder in = new StringBuilder();
    
    // Response queue
    public Queue<HTTPResponse> pending = new ArrayDeque<>();
    
    // Connection state
    public long lastActive = System.currentTimeMillis();
    public boolean closed = false;
    
    // Current request and server info
    private HTTPRequest currentRequest;
    private String listenAddress;
    
    // Flag to track if headers are already parsed
    private boolean headersParsed = false;
    
    // Position marker for parsing (how much we already consumed)
    private int consumedBytes = 0;
    
    // Buffer size limits
    private static final int MAX_ACCUMULATION_SIZE = 10 * 1024 * 1024; // 10MB max
    
    // NEW: Body streaming to file
    private File bodyTempFile = null;
    private RandomAccessFile bodyRAF = null;
    private FileChannel bodyFileChannel = null;
    private long expectedBodySize = -1;
    private long bodyBytesWritten = 0;
    private boolean streamingBody = false;
    
    // Temp directory for body files
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    
    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }
    
    /**
     * Append data from readBuf to allData (for headers only)
     */
    public void appendToAccumulation() {
        readBuf.flip();
        
        // Check size limit (for headers only now)
        if (allData.size() + readBuf.remaining() > MAX_ACCUMULATION_SIZE) {
            readBuf.clear();
            throw new RuntimeException("Request size exceeds maximum allowed: " + MAX_ACCUMULATION_SIZE);
        }
        
        // Write to ByteArrayOutputStream
        byte[] data = new byte[readBuf.remaining()];
        readBuf.get(data);
        allData.write(data, 0, data.length);
        
        readBuf.clear();
    }
    
    /**
     * Start streaming body to temporary file
     */
    public void startBodyStreaming(long contentLength) throws IOException {
        if (bodyTempFile != null) {
            throw new IllegalStateException("Body streaming already started");
        }
        
        // Create temp file
        bodyTempFile = File.createTempFile("http_body_", ".tmp", new File(TEMP_DIR));
        bodyTempFile.deleteOnExit();
        
        bodyRAF = new RandomAccessFile(bodyTempFile, "rw");
        bodyFileChannel = bodyRAF.getChannel();
        
        expectedBodySize = contentLength;
        bodyBytesWritten = 0;
        streamingBody = true;
        
        System.out.println("DEBUG: Started body streaming to " + bodyTempFile.getAbsolutePath());
    }
    
    /**
     * Write data to body file
     */
    public void writeToBodyFile(byte[] data, int offset, int length) throws IOException {
        if (!streamingBody || bodyFileChannel == null) {
            throw new IllegalStateException("Body streaming not started");
        }
        
        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        while (buf.hasRemaining()) {
            bodyFileChannel.write(buf);
        }
        
        bodyBytesWritten += length;
    }
    
    /**
     * Check if body streaming is complete
     */
    public boolean isBodyStreamingComplete() {
        return streamingBody && bodyBytesWritten >= expectedBodySize;
    }
    
    /**
     * Get body bytes written so far
     */
    public long getBodyBytesWritten() {
        return bodyBytesWritten;
    }
    
    /**
     * Get expected body size
     */
    public long getExpectedBodySize() {
        return expectedBodySize;
    }
    
    /**
     * Finish body streaming and return file path
     */
    public File finishBodyStreaming() throws IOException {
        if (!streamingBody) {
            return null;
        }
        
        if (bodyFileChannel != null) {
            bodyFileChannel.close();
        }
        if (bodyRAF != null) {
            bodyRAF.close();
        }
        
        streamingBody = false;
        
        System.out.println("DEBUG: Finished body streaming. Wrote " + bodyBytesWritten + " bytes");
        
        return bodyTempFile;
    }
    
    /**
     * Clean up body file
     */
    public void cleanupBodyFile() {
        try {
            if (bodyFileChannel != null) {
                bodyFileChannel.close();
            }
            if (bodyRAF != null) {
                bodyRAF.close();
            }
            if (bodyTempFile != null && bodyTempFile.exists()) {
                bodyTempFile.delete();
            }
        } catch (IOException e) {
            System.err.println("Error cleaning up body file: " + e.getMessage());
        }
        
        bodyTempFile = null;
        bodyRAF = null;
        bodyFileChannel = null;
        bodyBytesWritten = 0;
        streamingBody = false;
    }
    
    /**
     * Check if currently streaming body
     */
    public boolean isStreamingBody() {
        return streamingBody;
    }
    
    /**
     * Get ByteBuffer for parsing (view of unconsumed data)
     */
    public ByteBuffer getAccumulationForParsing() {
        byte[] allBytes = allData.toByteArray();
        
        // Create ByteBuffer starting from consumedBytes position
        ByteBuffer buf = ByteBuffer.wrap(allBytes, consumedBytes, allBytes.length - consumedBytes);
        
        return buf;
    }
    
    /**
     * Mark how many bytes we consumed from the buffer after parsing
     */
    public void markConsumed(int bytesConsumed) {
        consumedBytes += bytesConsumed;
    }
    
    /**
     * Compact - remove consumed data and keep unconsumed data
     */
    public void compactAccumulation() {
        if (consumedBytes == 0) {
            return; // Nothing to compact
        }
        
        byte[] allBytes = allData.toByteArray();
        int remaining = allBytes.length - consumedBytes;
        
        if (remaining > 0) {
            // Keep unconsumed data
            allData.reset();
            allData.write(allBytes, consumedBytes, remaining);
        } else {
            // Everything consumed
            allData.reset();
        }
        
        consumedBytes = 0;
    }
    
    /**
     * Clear accumulation buffer completely
     */
    public void clearAccumulation() {
        allData.reset();
        consumedBytes = 0;
        headersParsed = false;
        currentRequest = null;
        cleanupBodyFile();
    }
    
    /**
     * Get current size of accumulated data
     */
    public int getAccumulationSize() {
        return allData.size() - consumedBytes;
    }
    
    /**
     * Check if accumulation buffer has data
     */
    public boolean hasAccumulatedData() {
        return allData.size() > consumedBytes;
    }
    
    /**
     * Reset accumulation if it grew too large and is now empty
     */
    public void resetAccumulationIfLarge() {
        // If we accumulated a lot but now empty, reset to free memory
        if (allData.size() > 100 * 1024 && !hasAccumulatedData()) {
            allData = new ByteArrayOutputStream();
            consumedBytes = 0;
            System.out.println("DEBUG: Reset accumulation buffer to free memory");
        }
    }
    
    // Getters and setters
    
    public boolean areHeadersParsed() {
        return headersParsed;
    }
    
    public void setHeadersParsed(boolean parsed) {
        this.headersParsed = parsed;
    }
    
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
    
    public int getConsumedBytes() {
        return consumedBytes;
    }
}