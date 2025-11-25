import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NIO HTTP Server with Virtual Threads
 * Handles multiple concurrent requests on a SINGLE OS thread.
 * Requires Java 21+
 */
public class NioHttpServer {

    // Virtual thread executor
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Track active carrier threads for verification
    private static final Set<Long> carrierThreads = Collections.synchronizedSet(new HashSet<>());

    static {
        // Must be set before any virtual threads are created
        System.setProperty("jdk.virtualThreadScheduler.parallelism", "1");
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
    }

    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);

        int port = 8085;
        serverChannel.bind(new InetSocketAddress(port));

        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("=".repeat(60));
        System.out.println("NIO HTTP Server with Virtual Threads");
        System.out.println("Running on: http://localhost:" + port);
        System.out.println("OS Thread: " + Thread.currentThread());
        System.out.println("=".repeat(60));
        System.out.println("\nTest with:");
        System.out.println("  curl 'http://localhost:8085?delay=10'");
        System.out.println("\nSend multiple requests - they'll all process concurrently!");
        System.out.println("Even though each uses Thread.sleep()!\n");

        while (true) {
            selector.select();
            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();

                if (!key.isValid())
                    continue;

                if (key.isAcceptable()) {
                    handleAccept(key, selector);
                } else if (key.isReadable()) {
                    handleRead(key);
                }
            }
        }
    }

    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ);
            System.out.println("[ACCEPT] New connection from: " + clientChannel.getRemoteAddress());
            System.out.println("         Carrier thread: " + Thread.currentThread().getName());
        }    
    }

    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        int bytesRead = clientChannel.read(buffer);
        if (bytesRead == -1) {
            clientChannel.close();
            return;
        }

        buffer.flip();
        String request = new String(buffer.array(), 0, buffer.limit(), StandardCharsets.UTF_8);

        // Cancel read interest
        key.interestOps(0);


        // Submit to virtual thread
        executor.submit(() -> {
            try {
                handleRequestWithSleep(clientChannel, request, 5);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void handleRequestWithSleep(SocketChannel clientChannel,
            String request,
            long delaySecs) throws Exception {

        long startTime = System.currentTimeMillis();
        String virtualThreadName = Thread.currentThread().toString();
        carrierThreads.add(Thread.currentThread().getId());




        // String responseBody = 

        // String response = "HTTP/1.1 200 OK\r\n" +
        //         "Content-Type: text/html; charset=UTF-8\r\n" +
        //         "Content-Length: " + responseBody.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
        //         "Connection: close\r\n" +
        //         "\r\n" +
        //         responseBody;

        ByteBuffer responseBuffer = ByteBuffer.wrap("".getBytes(StandardCharsets.UTF_8));
        clientChannel.write(responseBuffer);
        clientChannel.close();

        System.out.println("[DONE] Response sent\n");
    }

    private static long parseDelay(String request) {
        try {
            if (request.contains("delay=")) {
                int start = request.indexOf("delay=") + 6;
                int end = request.indexOf(" ", start);
                if (end == -1)
                    end = request.indexOf("&", start);
                if (end == -1)
                    end = request.length();
                String delayStr = request.substring(start, end).trim();
                return Long.parseLong(delayStr);
            }
        } catch (Exception ignored) {
        }
        return 3; // default 3 seconds
    }
}
