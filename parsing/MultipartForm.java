package parsing;

import http.HTTPRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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
            fp.data = body;  // ✅ raw bytes — no corruption
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
    if (req.tempFilePath == null) throw new IOException("No temp file");

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
    if (safeName.isEmpty()) safeName = "upload.bin";

    // 3. Stream body (skip headers, trim final \r\n--boundary)
    Path out = Path.of(uploadDir, safeName).toAbsolutePath();
    Files.createDirectories(out.getParent());

    try (FileChannel src = FileChannel.open(req.tempFilePath, StandardOpenOption.READ);
         FileChannel dest = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

        // Get boundary
        String ct = req.headers.getOrDefault("content-type", "");
        int i = ct.indexOf("boundary=");
        if (i == -1) throw new IOException("No boundary");
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
}
