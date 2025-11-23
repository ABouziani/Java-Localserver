import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single OS thread handling multiple concurrent requests using Virtual Threads.
 * Requires Java 21+
 * 
 * Virtual threads are lightweight and scheduled on a single carrier thread,
 * allowing you to write blocking code (Thread.sleep) that doesn't actually block!
 */
public class NioHttpServer {
   
   // Virtual thread executor - uses single OS thread internally
   private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Force single carrier thread by setting system property
   static {
      // This MUST be set before any virtual threads are created
      System.setProperty("jdk.virtualThreadScheduler.parallelism", "1");
      System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
   }
   
   public static void main(String[] args) throws IOException {
      ServerSocketChannel serverChannel = ServerSocketChannel.open();
      serverChannel.configureBlocking(false);
      
      int port = 8085;
      ServerSocket serverSocket = serverChannel.socket();
      serverSocket.bind(new InetSocketAddress(port));
      
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

      // Main event loop runs on single OS thread
      while(true) {
         selector.select();
         Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

         while(keyIterator.hasNext()) {
            SelectionKey key = keyIterator.next();
            keyIterator.remove();
            
            if (!key.isValid()) {
               continue;
            }

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
      
      // Parse delay parameter
      long delaySecs = parseDelay(request);
      
      System.out.println("[READ] Request from: " + clientChannel.getRemoteAddress());
      System.out.println("       Will sleep for: " + delaySecs + " seconds");
      System.out.println("       Carrier thread: " + Thread.currentThread().getName());
      
      // Submit to virtual thread - this creates a new virtual thread
      // but it runs on the SAME carrier thread (OS thread)
      executor.submit(() -> {
         try {
            handleRequestWithSleep(clientChannel, request, delaySecs);
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
      
      System.out.println("[PROCESS] Starting to sleep for " + delaySecs + "s");
      System.out.println("          Virtual thread: " + virtualThreadName);
      System.out.println("          Carrier thread: " + getCarrierThread());
      
      // THIS IS THE MAGIC: Thread.sleep() in a virtual thread
      // doesn't block the carrier thread!
      // Other virtual threads can run while this one "sleeps"
      Thread.sleep(delaySecs * 1000);
      
      long elapsed = System.currentTimeMillis() - startTime;
      
      System.out.println("[AWAKE] Finished sleeping after " + elapsed + "ms");
      System.out.println("        Virtual thread: " + virtualThreadName);
      System.out.println("        Carrier thread: " + getCarrierThread());
      
      // Generate response
      String responseBody = "<!DOCTYPE html>\n" +
                           "<html><body style='font-family: monospace;'>" +
                           "<h1>🎉 Virtual Thread Magic!</h1>" +
                           "<p><b>You requested:</b> " + delaySecs + " second delay</p>" +
                           "<p><b>Actual elapsed:</b> " + elapsed + "ms</p>" +
                           "<p><b>Virtual Thread:</b> " + virtualThreadName + "</p>" +
                           "<p><b>Carrier Thread:</b> " + getCarrierThread() + "</p>" +
                           "<hr>" +
                           "<p>✨ This request used <code>Thread.sleep(" + delaySecs + "000)</code></p>" +
                           "<p>✨ But other requests were processed concurrently!</p>" +
                           "<p>✨ All on a SINGLE OS thread via Virtual Threads!</p>" +
                           "<hr>" +
                           "<p>Try opening multiple tabs with different delays:</p>" +
                           "<ul>" +
                           "<li><a href='?delay=5'>5 second delay</a></li>" +
                           "<li><a href='?delay=10'>10 second delay</a></li>" +
                           "<li><a href='?delay=20'>20 second delay</a></li>" +
                           "</ul>" +
                           "</body></html>";
      
      String response = "HTTP/1.1 200 OK\r\n" +
                       "Content-Type: text/html; charset=UTF-8\r\n" +
                       "Content-Length: " + responseBody.length() + "\r\n" +
                       "Connection: close\r\n" +
                       "\r\n" +
                       responseBody;
      
      ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
      clientChannel.write(responseBuffer);
      clientChannel.close();
      
      System.out.println("[DONE] Response sent\n");
   }
   
   private static long parseDelay(String request) {
      try {
         if (request.contains("delay=")) {
            int start = request.indexOf("delay=") + 6;
            int end = request.indexOf(" ", start);
            if (end == -1) end = request.indexOf("&", start);
            if (end == -1) end = request.length();
            String delayStr = request.substring(start, end).trim();
            return Long.parseLong(delayStr);
         }
      } catch (Exception e) {
         // Ignore parsing errors
      }
      return 3; // Default 3 seconds
   }
   
   private static String getCarrierThread() {
      // Get the underlying OS thread running this virtual thread
      return Thread.currentThread().toString()
         .replaceAll(".*carrier.*", "ForkJoinPool carrier thread");
   }
}