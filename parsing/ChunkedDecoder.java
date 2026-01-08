package parsing;

import java.io.*;
import java.nio.file.*;

import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;

public class ChunkedDecoder {

    public static HTTPResponse saveChunked(HTTPRequest req, ServerConfig cfg) throws IOException {
        Path chunkedFile = Path.of(req.tempFilePath.toUri());
        Path outputFile = Path.of("upload/body.txt");
        Files.createDirectories(outputFile.getParent());

        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(chunkedFile));
             BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(outputFile))) {

            while (true) {
                // 1. Read chunk size line (text)
                String line = readLine(bis);
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                int semicolonIndex = line.indexOf(';');
                if (semicolonIndex != -1) line = line.substring(0, semicolonIndex).trim();

                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(line, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chunk size: " + line);
                }

                if (chunkSize == 0) break; // last chunk

                // 2. Read exact chunk bytes
                int remaining = chunkSize;
                byte[] buffer = new byte[8192];
                while (remaining > 0) {
                    int toRead = Math.min(buffer.length, remaining);
                    int read = bis.read(buffer, 0, toRead);
                    if (read == -1) throw new IOException("Unexpected EOF in chunk");
                    bos.write(buffer, 0, read);
                    remaining -= read;
                }

                // 3. Skip CRLF after chunk
                if (bis.read() != '\r' || bis.read() != '\n') {
                    // Optional: ignore if your cURL output has just LF
                }
            }

            bos.flush();
            System.out.println("Body saved to: " + outputFile);
            return HTTPResponse.simple(200, "OK");

        } catch (IOException e) {
            Files.deleteIfExists(outputFile);
            return HTTPResponse.error(400, cfg);
        }
    }

    // Read a line ending with CRLF or LF
    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("UTF-8");
    }
}
