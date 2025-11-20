package parsing;

public class MultipartForm {
    static class FilePart { String name, filename; byte[] data; }
    List<FilePart> files = new ArrayList<>();
    static MultipartForm parse(HTTPRequest req) throws IOException {
        MultipartForm mf = new MultipartForm();
        String ct = req.headers.getOrDefault("Content-Type","");
        int i = ct.indexOf("boundary="); if (i==-1) return mf; String b = "--"+ct.substring(i+9);
        byte[] body = req.body!=null ? req.body.getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        String all = new String(body, StandardCharsets.ISO_8859_1);
        String[] chunks = all.split(Pattern.quote(b));
        for (String ch: chunks) {
            if (ch.trim().isEmpty() || ch.equals("--")) continue;
            int hEnd = ch.indexOf("\r\n\r\n"); if (hEnd==-1) continue;
            String hdr = ch.substring(0,hEnd);
            String data = ch.substring(hEnd+4, ch.length()-2);
            String fn = null; String name = null;
            for (String line: hdr.split("\r\n")) {
                if (line.startsWith("Content-Disposition")) {
                    int fnIdx = line.indexOf("filename=\""); if (fnIdx!=-1) { fn = line.substring(fnIdx+10, line.indexOf('"', fnIdx+10)); }
                    int nmIdx = line.indexOf("name=\""); if (nmIdx!=-1) { name = line.substring(nmIdx+6, line.indexOf('"', nmIdx+6)); }
                }
            }
            if (fn!=null) {
                FilePart p = new FilePart(); p.name=name; p.filename=fn; p.data=data.getBytes(StandardCharsets.ISO_8859_1); mf.files.add(p);
            }
        }
        return mf;
    }
}