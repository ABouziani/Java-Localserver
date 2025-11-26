package http;

import java.util.HashMap;
import java.util.Map;

public class HTTPRequest {

    public String method;
    public String path;
    String version;
    public Map<String, String> headers = new HashMap<>();
    public String body;
    public boolean valid = false;
    public String remain = "";

    static HTTPRequest invalid() {
        HTTPRequest r = new HTTPRequest();
        r.valid = false;
        return r;
    }
}
