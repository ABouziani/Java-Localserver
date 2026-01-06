package client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

import http.HTTPRequest;
import http.HTTPResponse;

public class ClientState {
    public SocketChannel ch;
    
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(8 * 1024);
    public ByteArrayOutputStream allData = new ByteArrayOutputStream();
    public ByteBuffer accumulationBuf =  ByteBuffer.allocateDirect(1);

    public ByteBuffer writeBuf = null;
    
    @Deprecated
    public StringBuilder in = new StringBuilder();
    
    public Queue<HTTPResponse> pending = new ArrayDeque<>();
    
    public long lastActive = System.currentTimeMillis();
    public boolean closed = false;
    
    // Request being built
    private HTTPRequest currentRequest;
    private String listenAddress;
    
    // NEW: Flag to track if headers are already parsed
    private boolean headersParsed = false;
    
    private static final int MAX_ACCUMULATION_SIZE = 10 * 1024 * 1024;
    
    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }
    
    public void appendToAccumulation() throws IOException {
        readBuf.flip();

        byte[] tmp = new byte[readBuf.remaining()];
        readBuf.get(tmp);            
        allData.write(tmp);    

        readBuf.clear();
    }
 
    private void growAccumulationBuffer(int additionalSpace) {
        int currentSize = accumulationBuf.position();
        int currentCapacity = accumulationBuf.capacity();
        int neededCapacity = currentSize + additionalSpace;
        
        int newCapacity = Math.max(currentCapacity * 2, neededCapacity);
        
        if (newCapacity > MAX_ACCUMULATION_SIZE) {
            newCapacity = MAX_ACCUMULATION_SIZE;
        }
        
        if (neededCapacity > MAX_ACCUMULATION_SIZE) {
            throw new RuntimeException("Request size exceeds maximum allowed: " + MAX_ACCUMULATION_SIZE);
        }
        
        ByteBuffer newBuf = ByteBuffer.allocate(newCapacity);
        
        accumulationBuf.flip();
        newBuf.put(accumulationBuf);
        
        accumulationBuf = newBuf;
        
        System.out.println("DEBUG: Accumulation buffer grown to " + newCapacity + " bytes");
    }
    
    public ByteBuffer getAccumulationForParsing() {
        byte[] data = allData.toByteArray();
        this.accumulationBuf = ByteBuffer.wrap(data);
        return accumulationBuf;
    }
    
    public void compactAccumulation() {
        accumulationBuf.compact();
    }
    
    public void clearAccumulation() {
        accumulationBuf.clear();
        headersParsed = false; // Reset flag when clearing
        currentRequest = null;
    }
 
    public int getAccumulationSize() {
        return accumulationBuf.position();
    }
    
    public boolean hasAccumulatedData() {
        return accumulationBuf.position() > 0;
    }
    
    public void resetAccumulationIfLarge() {
        if (accumulationBuf.capacity() > 100 * 1024 && accumulationBuf.position() == 0) {
            accumulationBuf = ByteBuffer.allocate(16 * 1024);
            System.out.println("DEBUG: Reset accumulation buffer to initial size");
        }
    }
    
    // NEW: Getters and setters for headersParsed flag
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
}