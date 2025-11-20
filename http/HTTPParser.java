package http;

public class HTTPParser {
    // returns null if incomplete. If complete, returns HTTPRequest with remain string (unparsed tail)
    static HTTPRequest tryParse(String raw) {
        int headersEnd = raw.indexOf("\r\n\r\n");
        if (headersEnd==-1) return null;
        String head = raw.substring(0, headersEnd+4);
        String[] lines = head.split("\r\n");
        String[] start = lines[0].split(" ",3);
        if (start.length<3) return HTTPRequest.invalid();
        String method = start[0]; String path = start[1]; String ver = start[2];
        Map<String,String> headers = new HashMap<>();
        for (int i=1;i<lines.length;i++){
            String line = lines[i]; if (line.isEmpty()) continue; int idx=line.indexOf(':'); if (idx==-1) continue; String k=line.substring(0,idx); String v=line.substring(idx+1).trim(); headers.put(k,v);
        }
        int contentLength = headers.containsKey("Content-Length") ? Integer.parseInt(headers.get("Content-Length")) : -1;
        boolean chunked = "chunked".equalsIgnoreCase(headers.getOrDefault("Transfer-Encoding",""));
        int totalLen = headersEnd+4;
        String body = null; String remain = raw.substring(totalLen);
        if (chunked) {
            // need to parse chunks: look for terminating 0\r\n\r\n
            int term = raw.indexOf("\r\n0\r\n\r\n", totalLen-0);
            if (term==-1) return null;
            String chunkPart = raw.substring(totalLen, term+5);
            body = decodeChunks(chunkPart);
            remain = raw.substring(term+7);
        } else if (contentLength>=0) {
            if (remain.length() < contentLength) return null;
            body = remain.substring(0, contentLength);
            remain = remain.substring(contentLength);
        }
        HTTPRequest req = new HTTPRequest();
        req.method = method; req.path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        req.version = ver; req.headers = headers; req.body = body; req.valid = true; req.remain = remain;
        return req;
    }
    static String decodeChunks(String s) {
        StringBuilder out = new StringBuilder(); int i=0; while (i<s.length()){
            int cr = s.indexOf('\r', i); if (cr==-1) break; String lenHex = s.substring(i,cr); int len = Integer.parseInt(lenHex.trim(), 16); if (len==0) break; i = cr+2; out.append(s.substring(i, i+len)); i+=len+2; }
        return out.toString();
    }
}