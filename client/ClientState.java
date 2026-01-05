package client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Queue;

import http.HTTPRequest;
import http.HTTPResponse;

public class ClientState {
    public SocketChannel ch;
    public ByteBuffer readBuf = ByteBuffer.allocateDirect(8 * 1024);
    public ByteBuffer writeBuf = null;
    public byte[] in = new byte[0];
    public Queue<HTTPResponse> pending = new ArrayDeque<>();
    public long lastActive = System.currentTimeMillis();
    public boolean closed = false;
    public HTTPRequest request;
    public int indexOfHeadersEnd = -1;
    public boolean headersParsed = false;
    private String listenAddress;
    // body handling
    public long expectedLength = -1;
    public long receivedLength = 0;

    // streaming upload
    public FileChannel fileOut;
    public Path tempFile;

    // chunked handling
    public boolean chunked = false;
    public int currentChunkSize = -1;

    public ClientState(SocketChannel ch) {
        this.ch = ch;
    }

    public int indexOfHeadersEnd() {
        // byte[] target = "\r\n\r\n".getBytes();
        for (int i = 0; i <= this.in.length - 4; i++) {
            if (this.in[i] == '\r' &&
                    this.in[i + 1] == '\n' &&
                    this.in[i + 2] == '\r' &&
                    this.in[i + 3] == '\n') {
                this.indexOfHeadersEnd = i + 4;
                return i + 4;
            }
        }
        return -1;
    }

    public void openTempFile() throws IOException {
     
     
        Path uploadDir = Paths.get("uploads");
        Files.createDirectories(uploadDir); // ensure exists

        this.tempFile = Files.createTempFile(uploadDir, "upload_", ".bin");
        this.fileOut = FileChannel.open(this.tempFile, StandardOpenOption.WRITE);
    }

    public void handleContentLengthBody() throws IOException {
        int bodyBytes = this.in.length - this.indexOfHeadersEnd;
        int toWrite = (int) Math.min(this.expectedLength - this.receivedLength, bodyBytes);

        this.fileOut.write(ByteBuffer.wrap(this.in, this.indexOfHeadersEnd, toWrite));
        this.receivedLength += toWrite;
    }

    public void handleChunkedBody() throws IOException {
        // minimal chunk processor (no extensions)
        int i = this.indexOfHeadersEnd;

        while (i < this.in.length) {

            if (this.currentChunkSize == -1) {
                int lineEnd = findCRLF(this.in, i);
                if (lineEnd == -1)
                    return;

                String hex = new String(this.in, i, lineEnd - i, java.nio.charset.StandardCharsets.US_ASCII);
                this.currentChunkSize = Integer.parseInt(hex.trim(), 16);
                i = lineEnd + 2;

                if (this.currentChunkSize == 0) {
                    this.fileOut.close();
                    System.out.println("Chunked upload saved to " + this.tempFile);
                    return;
                }
            }

            int remaining = this.in.length - i;
            int toWrite = Math.min(this.currentChunkSize, remaining);

            this.fileOut.write(ByteBuffer.wrap(this.in, i, toWrite));
            this.currentChunkSize -= toWrite;
            i += toWrite;

            if (this.currentChunkSize == 0 && remaining >= 2) {
                i += 2;
                this.currentChunkSize = -1;
            }
        }
    }

    private int findCRLF(byte[] b, int start) {
        for (int i = start; i < b.length - 1; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n')
                return i;
        }
        return -1;
    }

    public String getHeaderString() {
        int end = indexOfHeadersEnd();
        if (end == -1)
            return null;

        byte[] headers = new byte[end];
        System.arraycopy(this.in, 0, headers, 0, end);
        return new String(headers, StandardCharsets.ISO_8859_1);
    }

    public HTTPRequest getCurrentRequest() {
        return request;
    }

    public int getIndexOfHeadersEnd() {
        return this.indexOfHeadersEnd;
    }

    public void setCurrentRequest(HTTPRequest c) {
        request = c;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String l) {
        listenAddress = l;
    }
}