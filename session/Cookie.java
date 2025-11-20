package session;

public class Cookie {
    static String getCookie(String header, String name) {
        if (header==null) return null;
        String[] parts = header.split(";");
        for (String p: parts) { String[] kv = p.trim().split("=",2); if (kv.length==2 && kv[0].equals(name)) return kv[1]; }
        return null;
    }
}