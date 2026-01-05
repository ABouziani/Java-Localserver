package parsing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import http.HTTPRequest;

public class MultipartForm {

    public static class FilePart {
        public String name;
        public String filename;
        public byte[] data;
    }

    public List<FilePart> files = new ArrayList<>();

    /**
     * Parse a multipart/form-data request from a temp file
     */
    public static MultipartForm parse(HTTPRequest req) throws IOException {
        MultipartForm mf = new MultipartForm();

        String ct = req.headers.getOrDefault("content-type", "");
        int i = ct.indexOf("boundary=");
        if (i == -1)
            return mf; // not multipart

        String boundary = "--" + ct.substring(i + 9);
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);

        Path tempFile = req.tempFilePath;

        // Open the temp file channel
        try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192); // 8 KB buffer
            byte[] leftover = new byte[0]; // leftover bytes from previous read

            while (fc.read(buffer) > 0) {
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                buffer.clear();

                // prepend leftover from previous read
                byte[] data = new byte[leftover.length + chunk.length];
                System.arraycopy(leftover, 0, data, 0, leftover.length);
                System.arraycopy(chunk, 0, data, leftover.length, chunk.length);

                int pos;
                while ((pos = indexOf(data, boundaryBytes)) != -1) {
                    byte[] partBytes = new byte[pos];
                    System.arraycopy(data, 0, partBytes, 0, pos);

                    FilePart fp = parsePart(partBytes);
                    if (fp != null) mf.files.add(fp);

                    // remove processed part + boundary from data
                    int newLen = data.length - (pos + boundaryBytes.length);
                    byte[] newData = new byte[newLen];
                    System.arraycopy(data, pos + boundaryBytes.length, newData, 0, newLen);
                    data = newData;
                }

                leftover = data; // save leftover for next read
            }
        }

        return mf;
    }

    /**
     * Helper: find a byte array pattern inside another byte array
     */
    private static int indexOf(byte[] data, byte[] pattern) {
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * Parse a single part (headers + body)
     */
    private static FilePart parsePart(byte[] part) {
        // Find separator between headers and body
        int sep = -1;
        for (int i = 0; i < part.length - 3; i++) {
            if (part[i] == '\r' && part[i + 1] == '\n' && part[i + 2] == '\r' && part[i + 3] == '\n') {
                sep = i;
                break;
            }
        }
        if (sep == -1) return null; // invalid part

        byte[] headerBytes = new byte[sep];
        System.arraycopy(part, 0, headerBytes, 0, sep);
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);

        byte[] body = new byte[part.length - (sep + 4)];
        System.arraycopy(part, sep + 4, body, 0, body.length);

        String fn = null;
        String name = null;
        for (String line : headers.split("\r\n")) {
            if (line.startsWith("Content-Disposition")) {
                int fnIdx = line.indexOf("filename=\"");
                if (fnIdx != -1)
                    fn = line.substring(fnIdx + 10, line.indexOf('"', fnIdx + 10));
                int nmIdx = line.indexOf("name=\"");
                if (nmIdx != -1)
                    name = line.substring(nmIdx + 6, line.indexOf('"', nmIdx + 6));
            }
        }

        if (fn != null) {
            FilePart fp = new FilePart();
            fp.filename = fn;
            fp.name = name;
            fp.data = body; // keep binary as bytes
            return fp;
        }

        return null;
    }

    /**
     * Save all parts to disk in upload folder
     */
    public void saveAll(String uploadDirStr) throws IOException {
        Path uploadDir = Path.of(uploadDirStr);
        if (!Files.exists(uploadDir)) Files.createDirectories(uploadDir);

        for (FilePart fp : files) {
            Path out = uploadDir.resolve(fp.filename);
            Files.write(out, fp.data);
            System.out.println("Saved file: " + out.toString());
        }
    }
}
