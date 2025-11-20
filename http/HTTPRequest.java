package http;

public class HTTPRequest {

    String method, path, version;
    Map<String, String> headers = new HashMap<>();
    String body;
    boolean valid = false;
    String remain = "";

    static HTTPRequest invalid() {
        HTTPRequest r = new HTTPRequest();
        r.valid = false;
        return r;
    }
}
