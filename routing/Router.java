package routing;

import cgi.CGIHandler;
import configurations.ServerConfig;
import http.HTTPRequest;
import http.HTTPResponse;
import parsing.ChunkedDecoder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import session.Session;

;

public class Router {

    private final ServerConfig cfg;

    public Router(ServerConfig cfg) {
        this.cfg = cfg;
    }

    public HTTPResponse route(HTTPRequest req, Session sess, CGIHandler cgi) throws IOException {

        // basic validation
        if (!req.valid) {
            return HTTPResponse.error(400, cfg);
        }
        // find route by prefix (simple longest match)
        Route matched = null;
        String matchedPath = "";
        for (var en : cfg.getRoutes().entrySet()) {
            String p = en.getKey();
            if (req.path.startsWith(p) && p.length() >= matchedPath.length()) {
                matched = en.getValue();
                matchedPath = p;
            }
        }
        if (matched == null) {
            return HTTPResponse.errorWithBody(404, "Path not found", cfg);
        }
        // redirect

        if (!matched.methods.isEmpty() && !matched.methods.contains(req.method)) {
            return HTTPResponse.error(405, cfg);
        }
        // map path
        Path fsroot = Paths.get(matched.root != null ? matched.root : cfg.getRoot());
        String rel = req.path.substring(matchedPath.length());
        // if (rel.equals("") || rel.equals("/")) {
        // rel = "/" + matched.index;
        // }
        Path target = fsroot.resolve(rel).normalize();
        if (!target.startsWith(fsroot)) {
            return HTTPResponse.error(403, cfg);
        }
        File f = target.toFile();

        // CGI handling by extension
        String ext = getExt(f.getName());
        if (matched.cgi.containsKey(ext) || cfg.getRoutes().values().stream().anyMatch(r -> r.cgi.containsKey(ext))) {
            // run cgi
            return cgi.execute(req, f);
        }
        // methods: GET -> serve file, DELETE -> delete file, POST -> accept upload if
        // multipart
        switch (req.method) {
            case "GET":
                if (f.isDirectory()) {
                    File idx = new File(f, matched.index);
                    if (idx.exists()) {
                        return HTTPResponse.serveFile(idx, true);
                    }
                    if (matched.dirList) {
                        return HTTPResponse.dirListing(f, rel);
                    }
                    if (matched.redirect != null) {
                        return HTTPResponse.redirect(matched.redirect);
                    }
                    return HTTPResponse.error(403, cfg);
                }
                if (!f.exists())
                    return HTTPResponse.error(404, cfg);
                return HTTPResponse.serveFile(f, true);
            case "DELETE":
                if (f.isDirectory()) {
                    return HTTPResponse.error(403, cfg);
                }
                if (!f.exists())
                    return HTTPResponse.error(404, cfg);
                if (f.delete()) {
                    return HTTPResponse.simple(200, "Deleted");
                }
                if (matched.redirect != null) {
                    return HTTPResponse.redirect(matched.redirect);
                }
                return HTTPResponse.error(500, cfg);
            case "POST":
                // Check if chunked transfer encoding first
                if (req.isChunked) {
                    String filename = req.headers.get("x-filename");

                    if (filename == null || filename.isEmpty()) {
                        if (req.tempFilePath != null && Files.exists(req.tempFilePath)) {
                            try {
                                byte[] fileBuf = new byte[8192];
                                int readLen = (int) Math.min(8192, Files.size(req.tempFilePath));
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
                                e.printStackTrace();
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
                return HTTPResponse.simple(200, "OK");
            default:
                return HTTPResponse.error(405, cfg);
        }
    }

    private String getExt(String n) {
        int i = n.lastIndexOf('.');
        return i == -1 ? "" : n.substring(i + 1);
    }

    private String generateFilenameFromContentType(String contentType) {
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
