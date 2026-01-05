package client;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

import http.HTTPRequest;
import http.HTTPResponse;

public class ClientState {
    public SocketChannel ch;
    
    // Read buffer for socket reads (fixed size 8KB)
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(8 * 1024);
    
    // Accumulation buffer for large requests (grows dynamically)
    public ByteBuffer accumulationBuf = ByteBuffer.allocate(16 * 1024); // 16KB initial
    
    // Write buffer
    public ByteBuffer writeBuf = null;
    
    // Legacy string buffer (deprecated, kept for backward compatibility)
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
    
    // Buffer size limits
    private static final int MAX_ACCUMULATION_SIZE = 10 * 1024 * 1024; // 10MB max
    
    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }
    
    /**
     * Append data from readBuf to accumulationBuf
     * Automatically grows accumulationBuf if needed
     */
    public void appendToAccumulation() {
        // Prepare read buffer for reading
        readBuf.flip();
        
        // Check if we need to grow accumulation buffer
        if (accumulationBuf.remaining() < readBuf.remaining()) {
            growAccumulationBuffer(readBuf.remaining());
        }
        
        // Append data
        accumulationBuf.put(readBuf);
        
        // Clear read buffer for next read
        readBuf.clear();
    }
    
    /**
     * Grow accumulation buffer to accommodate more data
     */
    private void growAccumulationBuffer(int additionalSpace) {
        int currentSize = accumulationBuf.position();
        int currentCapacity = accumulationBuf.capacity();
        int neededCapacity = currentSize + additionalSpace;
        
        // Calculate new capacity (double or exact need, whichever is larger)
        int newCapacity = Math.max(currentCapacity * 2, neededCapacity);
        
        // Cap at maximum size
        if (newCapacity > MAX_ACCUMULATION_SIZE) {
            newCapacity = MAX_ACCUMULATION_SIZE;
        }
        
        // Check if we exceeded maximum
        if (neededCapacity > MAX_ACCUMULATION_SIZE) {
            throw new RuntimeException("Request size exceeds maximum allowed: " + MAX_ACCUMULATION_SIZE);
        }
        
        // Create new larger buffer
        ByteBuffer newBuf = ByteBuffer.allocate(newCapacity);
        
        // Copy existing data
        accumulationBuf.flip();
        newBuf.put(accumulationBuf);
        
        // Replace old buffer
        accumulationBuf = newBuf;
        
        System.out.println("DEBUG: Accumulation buffer grown to " + newCapacity + " bytes");
    }
    
    /**
     * Get accumulation buffer ready for parsing
     * Flips the buffer to read mode
     */
    public ByteBuffer getAccumulationForParsing() {
        accumulationBuf.flip();
        return accumulationBuf;
    }
    
    /**
     * Compact accumulation buffer after parsing
     * Moves remaining data to beginning
     */
    public void compactAccumulation() {
        accumulationBuf.compact();
    }
    
    /**
     * Clear accumulation buffer completely
     */
    public void clearAccumulation() {
        accumulationBuf.clear();
    }
    
    /**
     * Get current size of accumulated data
     */
    public int getAccumulationSize() {
        return accumulationBuf.position();
    }
    
    /**
     * Check if accumulation buffer has data
     */
    public boolean hasAccumulatedData() {
        return accumulationBuf.position() > 0;
    }
    
    /**
     * Reset accumulation buffer to initial size if it grew too large
     * Call this after processing a request to free memory
     */
    public void resetAccumulationIfLarge() {
        // If buffer grew beyond 100KB and is now empty, reset to initial size
        if (accumulationBuf.capacity() > 100 * 1024 && accumulationBuf.position() == 0) {
            accumulationBuf = ByteBuffer.allocate(16 * 1024);
            System.out.println("DEBUG: Reset accumulation buffer to initial size");
        }
    }
    
    // Getters and Setters
    
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
}