package client;

public class ClientState {
    public SocketChannel ch;
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(8*1024);
    public ByteBuffer writeBuf = null;
    public StringBuilder in = new StringBuilder();
    public Queue<HTTPResponse> pending = new ArrayDeque<>();
    public long lastActive = System.currentTimeMillis();
    public boolean closed = false;
    public ClientState(SocketChannel ch){this.ch=ch;}
}