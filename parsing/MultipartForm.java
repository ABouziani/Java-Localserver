package parsing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import client.ClientState;
import http.HTTPRequest;

public class MultipartForm {
    

    public static int parse(ClientState state) throws IOException {
        HTTPRequest req = state.request;
        String ct = req.headers.getOrDefault("content-type", "");
        int i = ct.indexOf("boundary=");
        if (i == -1)
            return -1;
        String b = "--" + ct.substring(i + 9);
        int bondaryLength = b.length();
        byte[] body = state.in != null ? state.in.toByteArray() : new byte[0];
        String all = new String(body, StandardCharsets.ISO_8859_1);
        String[] chunks = all.split(Pattern.quote(b));
        if (chunks.length < 3)return -1;
        int writtenBytes = bondaryLength*(chunks.length-1);
      
        for (String ch : chunks) {
            
            if (ch.trim().isEmpty() || ch.equals("--"))
                continue;
            int hEnd = ch.indexOf("\r\n\r\n");
            if (hEnd == -1)
                continue;
            String hdr = ch.substring(0, hEnd);
            
            if (hEnd+4 > ch.length() -2) continue;
            String data = ch.substring(hEnd + 4, ch.length() - 2);
            String fn = null;
            String name = null;
            for (String line : hdr.split("\r\n")) {
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
                FilePart p = new FilePart();
                p.name = name;
                p.filename = fn;
                state.files.add(p);
                state.fileOut.write(ByteBuffer.wrap(data.getBytes(StandardCharsets.ISO_8859_1)));
                writtenBytes+=ch.length();
            }
        }
        return writtenBytes;
    }
}