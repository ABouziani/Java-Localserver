package http;

public class HTTPParserDebug {
    public static void main(String[] args) {

        System.out.println("=== DEBUG Test 2: Simple POST ===\n");

        String body = "{\"name\":\"John\",\"age\":30}";
        System.out.println("Body string: " + body);
        System.out.println("Body length: " + body.length());
        System.out.println("Body bytes: " + body.getBytes().length);

        String raw = "POST /api/users HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;

        HTTPRequest req = HTTPParser.ParseHeaders(raw);

        if (req == null) {
            System.out.println("❌ Parser returned NULL");
        } else if (!req.valid) {
            System.out.println("❌ Parser returned INVALID");
        } else {
            System.out.println("✅ Parser returned VALID");
            System.out.println("Method: " + req.method);
            System.out.println("Path: " + req.path);
            System.out.println("Content-Type: " + req.headers.get("content-type"));
            System.out.println("Content-Length header: " + req.headers.get("content-length"));
            System.out.println("Body: " + req.body);
            System.out.println("Body matches: " + req.body.equals(body));

            // Test condition
            boolean pass = req != null &&
                    req.valid &&
                    req.method.equals("POST") &&
                    req.path.equals("/api/users") &&
                    req.headers.get("content-type").equals("application/json") &&
                    req.headers.get("content-length").equals(String.valueOf(body.length())) &&
                    req.body.equals(body);

            System.out.println("\nTest checks:");
            System.out.println("  req != null: " + (req != null));
            System.out.println("  req.valid: " + req.valid);
            System.out.println("  method == POST: " + req.method.equals("POST"));
            System.out.println("  path == /api/users: " + req.path.equals("/api/users"));
            System.out.println("  content-type == application/json: "
                    + req.headers.get("content-type").equals("application/json"));
            System.out.println("  content-length == " + body.length() + ": "
                    + req.headers.get("content-length").equals(String.valueOf(body.length())));
            System.out.println("  body matches: " + req.body.equals(body));
            System.out.println("\nFinal pass: " + pass);
        }

        System.out.println("\n=== DEBUG Test 5: Incomplete Body ===\n");

        String raw2 = "POST /api/data HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: 100\r\n" +
                "\r\n" +
                "Only 20 bytes here";

        System.out.println("Expected: 100 bytes");
        System.out.println("Actual: " + "Only 20 bytes here".length() + " bytes");

        HTTPRequest req2 = HTTPParser.ParseHeaders(raw2);

        if (req2 == null) {
            System.out.println("✅ Parser returned NULL (correct - incomplete)");
        } else if (!req2.valid) {
            System.out.println("❌ Parser returned INVALID (should be NULL)");
        } else {
            System.out.println("❌ Parser returned VALID (should be NULL)");
        }

        System.out.println("\nTest expects: req == null");
        System.out.println("Actual: req == " + (req2 == null ? "null" : "not null"));
        System.out.println("Pass: " + (req2 == null));
    }
}