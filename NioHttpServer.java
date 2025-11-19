
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

        ServerSocket serverSocket = serverChannel.socket();
        serverSocket.bind(new InetSocketAddress(8085));

        // Create selector to manage multiple channels
        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("NIO HTTP Server running on http://localhost:8080");

        while (true) {
            selector.select(); // wait for events
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                System.out.println("----------------");
                SelectionKey key = keys.next();
                if (key.isValid()) {

                    System.out.println(key);
                }
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
        System.out.println("Client connected: " + client.getRemoteAddress());
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

        String[] parts = request.split("\r\n\r\n", 2);
        String headers = parts[0];
        String postBody = parts.length > 1 ? parts[1] : "";

        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
        }

        while (postBody.length() < contentLength) {
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int readMore = client.read(temp);

            if (readMore == -1) {
                break;
            }

            temp.flip();
            postBody += new String(temp.array(), 0, readMore);
        }

        System.out.println("Post body: \n" + postBody);

        String[] lines = request.split("\r\n");
        String[] firstLine = lines[0].split(" ");

        String method = firstLine[0];
        String path = firstLine[1];
        System.out.println("Received request:\n" + request);

        // String body;
        // if (method.equals("GET") && path.equals("/hello")) {
        //     body = "{\'msg\': \'hello world\'}";
        // } else if (method.equals("POST") && path.equals("/login")) {
        //     body = "{\"status\": \"logged\"}";
        // } else {
        //     body = "{\"error\": \"not found\"}";
        // }
        // String response = buildResponse(body);
        String json = "{\"received\": " + postBody + "}";
        String response = buildResponse(json);

        ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
        client.write(responseBuffer);

        client.close(); // close after response (simple HTTP/1.0 style)
    }

    static String buildResponse(String body) {
        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "\r\n"
                + body;
    }

}
