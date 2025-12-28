package http;

public class HTTPParserTests {

        private static int testsPassed = 0;
        private static int testsFailed = 0;

        public static void main(String[] args) {
                System.out.println("=== HTTPParser Tests ===\n");

                // Basic Tests
                test1_SimpleGET();
                test2_SimplePOST();
                test3_SimpleDELETE();

                // Incomplete Requests
                test4_IncompleteHeaders();
                test5_IncompleteBody();

                // Invalid Requests
                test6_InvalidMethod();
                test7_MalformedRequestLine();
                test8_POSTWithoutContentLength();
                test9_GETWithBody();
                test10_InvalidContentLength();

                // Summary
                System.out.println("\n=== Test Results ===");
                System.out.println("Passed: " + testsPassed);
                System.out.println("Failed: " + testsFailed);
                System.out.println("Total:  " + (testsPassed + testsFailed));
        }

        static void test1_SimpleGET() {
                String raw = "GET /users?page=1 HTTP/1.1\r\n" +
                                "Host: api.example.com\r\n" +
                                "Accept: application/json\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.method.equals("GET") &&
                                req.path.equals("/users?page=1") &&
                                req.version.equals("HTTP/1.1") &&
                                req.headers.get("host").equals("api.example.com") &&
                                req.headers.get("accept").equals("application/json") &&
                                req.body == null;

                report("Test 1: Simple GET Request", pass);
        }

        static void test2_SimplePOST() {
                String body = "{\"name\":\"John\",\"age\":30}";
                String raw = "POST /api/users HTTP/1.1\r\n" +
                                "Host: api.example.com\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: " + body.length() + "\r\n" +
                                "\r\n" +
                                body;

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.method.equals("POST") &&
                                req.path.equals("/api/users") &&
                                req.headers.get("content-type").equals("application/json") &&
                                req.headers.get("content-length").equals(String.valueOf(body.length())) &&
                                req.body.equals(body);

                report("Test 2: Simple POST Request", pass);
        }

        static void test3_SimpleDELETE() {
                String raw = "DELETE /users/123 HTTP/1.1\r\n" +
                                "Host: api.example.com\r\n" +
                                "Authorization: Bearer token123\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.method.equals("DELETE") &&
                                req.path.equals("/users/123") &&
                                req.headers.get("authorization").equals("Bearer token123") &&
                                req.body == null;

                report("Test 3: Simple DELETE Request", pass);
        }

        static void test4_IncompleteHeaders() {
                String raw = "GET /test HTTP/1.1\r\n" +
                                "Host: example.com\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req == null;

                report("Test 4: Incomplete Headers", pass);
        }

        static void test5_IncompleteBody() {
                String raw = "POST /api/data HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "Content-Length: 100\r\n" +
                                "\r\n" +
                                "Only 20 bytes here";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req == null;

                report("Test 5: Incomplete Body", pass);
        }

        static void test6_InvalidMethod() {
                String raw = "PUT /users/123 HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 6: Invalid Method (PUT)", pass);
        }

        static void test7_MalformedRequestLine() {
                String raw = "GET /users\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 7: Malformed Request Line", pass);
        }

        static void test8_POSTWithoutContentLength() {
                String raw = "POST /api/data HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n" +
                                "{\"data\":\"test\"}";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 8: POST Without Content-Length", pass);
        }

        static void test9_GETWithBody() {
                String raw = "GET /test HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "Content-Length: 10\r\n" +
                                "\r\n" +
                                "some data!";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 9: GET With Body", pass);
        }

        static void test10_InvalidContentLength() {
                String raw = "POST /api/data HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "Content-Length: -100\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 10: Invalid Content-Length", pass);
        }

        static void report(String testName, boolean pass) {
                if (pass) {
                        System.out.println("✅ " + testName);
                        testsPassed++;
                } else {
                        System.out.println("❌ " + testName);
                        testsFailed++;
                }
        }
}
