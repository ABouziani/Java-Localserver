
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class NioHttpServer {

    public static void main(String[] args) throws IOException {

        // Create a non-blocking server socket channel
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);

        int port = 8085;
        ServerSocket serverSocket = serverChannel.socket();
        serverSocket.bind(new InetSocketAddress(port));

        // Create selector to manage multiple channels
        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("NIO HTTP Server running on http://localhost:" + port);

        while (true) {
            selector.select(); // wait for events
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) {
                    continue;
                }

                if (key.isAcceptable()) {
                    handleAccept(key, selector);
                }
                if (key.isReadable()) {
                    handleRead(key);
                }
            }
        }
    }

    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel client = serverChannel.accept();
        client.configureBlocking(false);

        client.register(selector, SelectionKey.OP_READ);
        // System.out.println("Client connected: " + client.getRemoteAddress());
    }

    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int read = client.read(buffer);

        if (read == -1) {
            client.close();
            return;
        }

        // Convert bytes to string (HTTP request)
        buffer.flip();
        String request = new String(buffer.array(), 0, buffer.limit());
        String visibleRequest = request.replace("\r", "\\r").replace("\n", "\\n\n");
        System.out.println(visibleRequest);
        System.out.println("-----------------------------------------");

        var firstLine = request.split("\r\n\r\n");
        System.out.println(firstLine[0]);
        if (firstLine.length > 1 ) {
            System.out.println(firstLine[1]);
        }

        // String methode = firstLine.split(" ")[0];
        // String endpoint = firstLine.split(" ")[1];
    }
}
