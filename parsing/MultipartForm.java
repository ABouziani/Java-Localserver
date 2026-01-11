package parsing;

import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import routing.Route;

public class MultipartForm {

    public static class FilePart {

        public String name;
        public String filename;
        public byte[] data; // raw bytes — binary-safe
    }

    public List<FilePart> files = new ArrayList<>();

    /**
     * Parse multipart request — uses your original, proven logic.
     */
    public static MultipartForm parse(HTTPRequest req) throws IOException {

        MultipartForm mf = new MultipartForm();

        String ct = req.headers.getOrDefault("content-type", "");
        int i = ct.indexOf("boundary=");
        if (i == -1) {
            return mf;
        }

        String boundary = "--" + ct.substring(i + 9);
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);

        try (FileChannel fc = FileChannel.open(req.tempFilePath, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            byte[] leftover = new byte[0];

            while (fc.read(buffer) > 0) {
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                buffer.clear();

                byte[] data = new byte[leftover.length + chunk.length];
                System.arraycopy(leftover, 0, data, 0, leftover.length);
                System.arraycopy(chunk, 0, data, leftover.length, chunk.length);

                int pos;
                while ((pos = indexOf(data, boundaryBytes)) != -1) {
                    byte[] partBytes = new byte[pos];
                    System.arraycopy(data, 0, partBytes, 0, pos);

                    FilePart fp = parsePart(partBytes);
                    if (fp != null) {
                        mf.files.add(fp);
                    }

                    int newLen = data.length - (pos + boundaryBytes.length);
                    byte[] newData = new byte[newLen];
                    System.arraycopy(data, pos + boundaryBytes.length, newData, 0, newLen);
                    data = newData;
                }

                leftover = data;
            }
        }

        return mf;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static FilePart parsePart(byte[] part) {
        int sep = -1;
        for (int i = 0; i < part.length - 3; i++) {
            if (part[i] == '\r' && part[i + 1] == '\n' && part[i + 2] == '\r' && part[i + 3] == '\n') {
                sep = i;
                break;
            }
        }
        if (sep == -1) {
            return null;
        }

        byte[] headerBytes = new byte[sep];
        System.arraycopy(part, 0, headerBytes, 0, sep);
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);

        byte[] body = new byte[part.length - (sep + 4)];
        System.arraycopy(part, sep + 4, body, 0, body.length);

        String fn = null, name = null;
        for (String line : headers.split("\r\n")) {
            if (line.startsWith("Content-Disposition")) {
                int fnIdx = line.indexOf("filename=\"");
                if (fnIdx != -1) {
                    fn = line.substring(fnIdx + 10, line.indexOf('"', fnIdx + 10));
                }
                int nmIdx = line.indexOf("name=\"");
                if (nmIdx != -1) {
                    name = line.substring(nmIdx + 6, line.indexOf('"', nmIdx + 6));
                }
            }
        }

        if (fn != null) {
            FilePart fp = new FilePart();
            fp.filename = fn;
            fp.name = name;
            fp.data = body; // ✅ raw bytes — no corruption
            return fp;
        }

        return null;
    }

    /**
     * Save all parts — fast, safe, preserves binary data.
     */
    public void saveAll(String uploadDirStr) throws IOException {
        Path uploadDir = Path.of(uploadDirStr).toAbsolutePath();
        Files.createDirectories(uploadDir);

        for (FilePart fp : files) {
            if (fp.data == null || fp.filename == null) {
                continue;
            }

            // ✅ Preserve original name (v.mp4 → v.mp4)
            String safeName = fp.filename
                    .replaceAll("[^a-zA-Z0-9.\\-_ ]", "_")
                    .replaceAll("\\s+", "_");
            if (safeName.isEmpty()) {
                safeName = "upload.bin";
            }

            Path out = uploadDir.resolve(safeName).normalize();
            if (!out.startsWith(uploadDir)) {
                System.err.println("⚠️ Skipped: " + fp.filename);
                continue;
            }

            // ✅ Write raw bytes — no encoding, no corruption
            Files.write(out, fp.data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("✅ Saved: " + safeName + " (" + fp.data.length + " bytes)");
        }
    }

    public static void printTempFile(Path tempFile) throws IOException {
        if (tempFile == null || !Files.exists(tempFile)) {
            System.out.println("⚠️ temp file not found");
            return;
        }

        try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ)) {
            int maxBytes = (int) Math.min(fc.size(), 200);
            ByteBuffer buf = ByteBuffer.allocate(maxBytes);
            fc.read(buf);
            buf.flip();

            byte[] data = new byte[buf.remaining()];
            buf.get(data);

            // Hex dump
            System.out.println("🔍 First " + data.length + " bytes of temp file:");
            for (int i = 0; i < data.length; i += 16) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();

                for (int j = 0; j < 16 && i + j < data.length; j++) {
                    byte b = data[i + j];
                    hex.append(String.format("%02X ", b));
                    ascii.append((b >= 32 && b < 127) ? (char) b : '.');
                }

                System.out.printf("%04x  %-48s  %s%n", i, hex.toString(), ascii.toString());
            }
        }
    }

    public static void saveRawFile(HTTPRequest req, String uploadDir) throws IOException {
        if (req.tempFilePath == null)
            throw new IOException("No temp file");

        // 1. Extract filename from headers (minimal read)
        String filename = "upload.bin";
        long bodyStart = 0;

        try (FileChannel fc = FileChannel.open(req.tempFilePath, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(1024);
            StringBuilder headers = new StringBuilder();
            boolean foundEnd = false;

            while (!foundEnd && fc.read(buf) > 0) {
                buf.flip();
                for (int i = 0; i < buf.remaining() - 3; i++) {
                    if (buf.get(i) == '\r' && buf.get(i + 1) == '\n'
                            && buf.get(i + 2) == '\r' && buf.get(i + 3) == '\n') {
                        // Found \r\n\r\n — end of headers
                        byte[] hdrBytes = new byte[i];
                        buf.rewind();
                        buf.get(hdrBytes, 0, i);
                        String headerStr = new String(hdrBytes, StandardCharsets.ISO_8859_1);

                        // Extract filename="..."
                        int fnIdx = headerStr.indexOf("filename=\"");
                        if (fnIdx != -1) {
                            int start = fnIdx + 10;
                            int end = headerStr.indexOf('"', start);
                            if (end > start) {
                                filename = headerStr.substring(start, end);
                            }
                        }

                        bodyStart = fc.position() - (buf.remaining() - (i + 4));
                        foundEnd = true;
                        break;
                    }
                }
                if (!foundEnd) {
                    headers.append(StandardCharsets.ISO_8859_1.decode(buf));
                    buf.compact();
                }
            }
        }

        // 2. Sanitize filename
        String safeName = filename.replaceAll("[^a-zA-Z0-9.\\-_ ]", "_").replaceAll("\\s+", "_");
        if (safeName.isEmpty())
            safeName = "upload.bin";

        // 3. Stream body (skip headers, trim final \r\n--boundary)
        Path out = Path.of(uploadDir, safeName).toAbsolutePath();
        Files.createDirectories(out.getParent());

        try (FileChannel src = FileChannel.open(req.tempFilePath, StandardOpenOption.READ);
                FileChannel dest = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            // Get boundary
            String ct = req.headers.getOrDefault("content-type", "");
            int i = ct.indexOf("boundary=");
            if (i == -1)
                throw new IOException("No boundary");
            String boundary = "--" + ct.substring(i + 9).trim() + "--";
            byte[] finalBoundary = ("\r\n" + boundary).getBytes(StandardCharsets.ISO_8859_1);

            // Find end of body (last occurrence of \r\n--boundary--)
            long fileSize = src.size();
            long searchStart = Math.max(0, fileSize - finalBoundary.length - 100);
            src.position(searchStart);

            ByteBuffer tail = ByteBuffer.allocate((int) (fileSize - searchStart));
            src.read(tail);
            tail.flip();

            int boundaryPos = -1;
            for (int p = tail.remaining() - finalBoundary.length; p >= 0; p--) {
                boolean match = true;
                for (int b = 0; b < finalBoundary.length; b++) {
                    if (tail.get(p + b) != finalBoundary[b]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    boundaryPos = p;
                    break;
                }
            }

            long bodyEnd = fileSize;
            if (boundaryPos != -1) {
                bodyEnd = searchStart + boundaryPos;
            }

            // Stream body
            src.position(bodyStart);
            long bodySize = bodyEnd - bodyStart;
            long transferred = 0;
            while (transferred < bodySize) {
                long chunk = Math.min(bodySize - transferred, 65536);
                transferred += dest.transferFrom(src, transferred, chunk);
            }
        }

        System.out.println("✅ Saved: " + safeName + " (" + (new java.io.File(out.toString())).length() + " bytes)");
    }

    /////////////////////////
    public static HTTPResponse parseBody(HTTPRequest req, ServerConfig cfg, Route matched) throws IOException {
        if (req.isChunked) {
            String filename = req.headers.get("x-filename");

            if (filename == null || filename.isEmpty()) {
                if (req.tempFilePath != null && Files.exists(req.tempFilePath)) {
                    try {
                        byte[] fileBuf = new byte[500];
                        int readLen = (int) Math.min(fileBuf.length, Files.size(req.tempFilePath));
                        try (FileChannel fc = FileChannel.open(req.tempFilePath, StandardOpenOption.READ)) {
                            fc.read(ByteBuffer.wrap(fileBuf), 0);
                        }

                        int firstCRLF = -1;
                        for (int i = 0; i < readLen - 1; i++) {
                            if (fileBuf[i] == '\r' && fileBuf[i + 1] == '\n') {
                                firstCRLF = i;
                                break;
                            }
                        }

                        if (firstCRLF > 0) {
                            int dataStart = firstCRLF + 2;

                            // Search for Content-Disposition in the next chunk data
                            int searchLimit = Math.min(readLen, dataStart + 2048);
                            String searchData = new String(fileBuf, dataStart,
                                    Math.min(2000, searchLimit - dataStart), StandardCharsets.ISO_8859_1);

                            int fnIdx = searchData.indexOf("filename=\"");
                            if (fnIdx != -1) {
                                int start = fnIdx + 10;
                                int end = searchData.indexOf('"', start);
                                if (end > start && end - start < 256) {
                                    filename = searchData.substring(start, end);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Continue to fallback
                    }
                }
            }

            // If still no filename found, generate from Content-Type header
            if (filename == null || filename.isEmpty()) {
                String contentType = req.headers.getOrDefault("content-type", "application/octet-stream");
                filename = generateFilenameFromContentType(contentType);
            }

            String safeName = filename.replaceAll("[^a-zA-Z0-9.\\-_ ]", "_").replaceAll("\\s+", "_");
            Path out = Path.of("upload", safeName).toAbsolutePath();
            Files.createDirectories(out.getParent());
            return ChunkedDecoder.saveChunked(req, cfg, out);
        }

        byte[] headerBuf = new byte[4096];
        int headerLen = (int) Math.min(4096, Files.size(req.tempFilePath));
        try (FileChannel fc = FileChannel.open(req.tempFilePath, StandardOpenOption.READ)) {
            fc.read(ByteBuffer.wrap(headerBuf), 0);
        }
        if (req.tempFilePath == null || !Files.exists(req.tempFilePath)) {
            return HTTPResponse.error(500, cfg);
        }

        int headerEnd = -1;
        for (int i = 0; i <= headerLen - 4; i++) {
            if (headerBuf[i] == '\r' && headerBuf[i + 1] == '\n'
                    && headerBuf[i + 2] == '\r' && headerBuf[i + 3] == '\n') {
                headerEnd = i + 4; // skip \r\n\r\n
                break;
            }
        }
        if (headerEnd == -1) {
            return HTTPResponse.error(400, cfg);
        }

        // 2. Extract filename from body, or generate from Content-Type
        String headers = new String(headerBuf, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String filename = null;
        int fnIdx = headers.indexOf("filename=\"");
        if (fnIdx != -1) {
            int start = fnIdx + 10;
            int end = headers.indexOf('"', start);
            if (end > start) {
                filename = headers.substring(start, end);
            }
        }

        // If no filename found, generate from Content-Type header
        if (filename == null || filename.isEmpty()) {
            String contentType = req.headers.getOrDefault("content-type", "application/octet-stream");
            filename = generateFilenameFromContentType(contentType);
        }

        long bsize = headers.split("\r\n")[0].length() + 5;

        // 3. ✅ CRITICAL: Use Content-Length for body size
        long bodySize = req.expectedContentLength - headerEnd - bsize; // ← from HTTPParser
        // System.out.print(bodySize);
        if (bodySize <= 0) {
            return HTTPResponse.error(400, cfg);
        }

        // 4. Save body: [headerEnd, headerEnd + bodySize)
        String safeName = filename.replaceAll("[^a-zA-Z0-9.\\-_ ]", "_").replaceAll("\\s+", "_");
        Path out = Path.of("upload", safeName).toAbsolutePath();
        Files.createDirectories(out.getParent());
        try (var src = FileChannel.open(req.tempFilePath, StandardOpenOption.READ);
                FileChannel dest = FileChannel.open(out,
                        StandardOpenOption.CREATE, // create if not exists
                        StandardOpenOption.WRITE, // open for writing
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            src.position(headerEnd);
            long transferred = 0;
            if (bodySize <= 0) {
                System.err.println("⚠️ bodySize = " + bodySize + ", skipping transfer");
                // Still create empty file
                Files.write(out, new byte[0]);
            } else {
                while (transferred < bodySize) {
                    long chunk = Math.min(bodySize - transferred, 65536);
                    if (chunk <= 0) {
                        break; // safety

                    }
                    long written = dest.transferFrom(src, transferred, chunk);
                    if (written <= 0) {
                        break; // EOF

                    }
                    transferred += written;
                }
                System.out.println("✅ Transferred: " + transferred + "/" + bodySize);
            }
        }

        System.out.println("✅ Saved " + safeName + " (" + bodySize + " bytes)");
        if (matched.redirect != null) {
            return HTTPResponse.redirect(matched.redirect);
        }

        return null;
    }

    private static String generateFilenameFromContentType(String contentType) {
        // Extract the base type (without parameters like charset)
        String type = contentType.split(";")[0].trim();

        // Map content types to file extensions
        String extension = switch (type.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "application/xml", "text/xml" -> ".xml";
            case "text/plain" -> ".txt";
            case "text/html" -> ".html";
            case "text/csv" -> ".csv";
            case "application/zip" -> ".zip";
            case "application/x-gzip", "application/gzip" -> ".gz";
            case "video/mp4" -> ".mp4";
            case "audio/mpeg" -> ".mp3";
            case "audio/wav" -> ".wav";
            default -> ".bin";
        };

        // Generate filename with timestamp
        long timestamp = System.currentTimeMillis();
        return "upload_" + timestamp + extension;
    }

}