package parsing;

import java.io.*;
import java.nio.file.*;

import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;

public class ChunkedDecoder {

    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    public static class InvalidChunkException extends IOException {
        public InvalidChunkException(String message) {
            super(message);
        }
    }

    public static HTTPResponse saveChunked(HTTPRequest req, ServerConfig cfg, Path out) {

        Path chunkedFile = null;
        Path outputFile = out;

        try {

            chunkedFile = Path.of(req.tempFilePath.toUri());
            if (!Files.exists(chunkedFile)) {
                throw new InvalidChunkException("Temporary chunked file not found");
            }

            Files.createDirectories(outputFile.getParent());

            long maxSize = cfg.getClientMaxBody();

            long totalWritten = 0;
            boolean foundLastChunk = false;
            boolean headersSkipped = false;
            int headerEndPos = -1;

            try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(chunkedFile));
                    BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(outputFile));
                    ByteArrayOutputStream decodedChunks = new ByteArrayOutputStream();) {

                while (true) {

                    String line = readLine(bis);
                    if (line == null) {
                        if (!foundLastChunk) {
                            throw new InvalidChunkException("Missing terminating chunk (0\\r\\n\\r\\n)");
                        }
                        break;
                    }

                    line = line.trim();
                    if (line.isEmpty())
                        continue;

                    int semicolonIndex = line.indexOf(';');
                    if (semicolonIndex != -1) {
                        line = line.substring(0, semicolonIndex).trim();
                    }

                    if (line.isEmpty()) {
                        throw new InvalidChunkException("Empty chunk size line");
                    }

                    int chunkSize;
                    try {
                        chunkSize = Integer.parseInt(line, 16);
                    } catch (NumberFormatException e) {
                        throw new InvalidChunkException("Invalid hexadecimal chunk size: " + line);
                    }

                    if (chunkSize < 0) {
                        throw new InvalidChunkException("Negative chunk size not allowed");
                    }

                    if (chunkSize == 0) {
                        foundLastChunk = true;

                        int cr = bis.read();
                        int lf = bis.read();

                        if (cr == '\r' && lf == '\n') {
                            break;
                        } else if (cr == '\n') {
                            break;
                        } else if (cr == -1) {
                            break;
                        } else {
                            throw new InvalidChunkException("Data after terminating chunk not allowed");
                        }
                    }

                    if (totalWritten + chunkSize > maxSize) {
                        throw new PayloadTooLargeException(
                                "Request size exceeds maximum allowed: " + maxSize + " bytes");
                    }

                    int remaining = chunkSize;
                    byte[] buffer = new byte[Math.min(8192, chunkSize)];

                    while (remaining > 0) {
                        int toRead = Math.min(buffer.length, remaining);
                        int read = bis.read(buffer, 0, toRead);

                        if (read == -1) {
                            throw new InvalidChunkException(
                                    "Unexpected EOF while reading chunk data (expected " + remaining + " more bytes)");
                        }

                        decodedChunks.write(buffer, 0, read);
                        remaining -= read;
                        totalWritten += read;
                    }

                    int cr = bis.read();
                    int lf = bis.read();

                    if (cr == -1 || lf == -1) {
                        throw new InvalidChunkException("Unexpected EOF after chunk data");
                    }

                    if (cr != '\r' || lf != '\n') {
                        if (cr == '\n') {
                            continue;
                        } else {
                            throw new InvalidChunkException("Missing CRLF after chunk data");
                        }
                    }
                }

                if (!foundLastChunk) {
                    throw new InvalidChunkException("Missing terminating chunk (0\\r\\n\\r\\n)");
                }

                // Now we have all decoded chunks - skip multipart headers if present
                byte[] decodedData = decodedChunks.toByteArray();
                int dataStart = 0;

                // Look for \r\n\r\n (end of headers) in the decoded data
                for (int i = 0; i < decodedData.length - 3; i++) {
                    if (decodedData[i] == '\r' && decodedData[i + 1] == '\n'
                            && decodedData[i + 2] == '\r' && decodedData[i + 3] == '\n') {
                        dataStart = i + 4;
                        break;
                    }
                }

                // Write only the actual file data (skip multipart headers)
                bos.write(decodedData, dataStart, decodedData.length - dataStart);
                bos.flush();

                long actualBytes = decodedData.length - dataStart;
                System.out.println("Body saved to: " + outputFile + " (" + actualBytes + " bytes)");
                return HTTPResponse.simple(200, "OK");

            }

        } catch (PayloadTooLargeException e) {
            cleanupFiles(outputFile, chunkedFile);
            String body = "<html><body><h1>413 Payload Too Large</h1><p>" + e.getMessage() + "</p></body></html>";
            return HTTPResponse.errorWithBody(413, body, cfg);

        } catch (InvalidChunkException e) {
            cleanupFiles(outputFile, chunkedFile);
            String body = "<html><body><h1>400 Bad Request</h1><p>Invalid chunked encoding: " + e.getMessage()
                    + "</p></body></html>";
            return HTTPResponse.errorWithBody(400, body, cfg);

        } catch (IOException e) {
            cleanupFiles(outputFile, chunkedFile);
            String body = "<html><body><h1>500 Internal Server Error</h1><p>Failed to process chunked request.</p></body></html>";
            return HTTPResponse.errorWithBody(500, body, cfg);

        } catch (Exception e) {
            cleanupFiles(outputFile, chunkedFile);
            String body = "<html><body><h1>500 Internal Server Error</h1><p>Unexpected error occurred.</p></body></html>";
            return HTTPResponse.errorWithBody(500, body, cfg);
        }
    }

    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        int bytesRead = 0;
        int maxLineLength = 1024;

        while ((b = is.read()) != -1) {
            if (++bytesRead > maxLineLength) {
                throw new IOException("Chunk size line exceeds maximum length");
            }

            if (b == '\n')
                break;
            if (b != '\r')
                baos.write(b);
        }

        if (b == -1 && baos.size() == 0)
            return null;
        return baos.toString("UTF-8");
    }

    private static void cleanupFiles(Path... paths) {
        for (Path path : paths) {
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        }
    }
}