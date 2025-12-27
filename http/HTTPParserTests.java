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

        // ============================================================
        // Test 1: Simple GET Request
        // ============================================================
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

        // ============================================================
        // Test 2: Simple POST Request
        // ============================================================
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
                                req.headers.get("content-length").equals("27") &&
                                req.body.equals(body);

                report("Test 2: Simple POST Request", pass);
        }

        // ============================================================
        // Test 3: Simple DELETE Request
        // ============================================================
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

        // ============================================================
        // Test 4: Incomplete Headers (should return null)
        // ============================================================
        static void test4_IncompleteHeaders() {
                String raw = "GET /test HTTP/1.1\r\n" +
                                "Host: example.com\r\n";
                // Missing final \r\n\r\n

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req == null;

                report("Test 4: Incomplete Headers", pass);
        }

        // ============================================================
        // Test 5: Incomplete Body (should return null)
        // ============================================================
        static void test5_IncompleteBody() {
                String raw = "POST /api/data HTTP/1.1\r\n" +
                                "Content-Length: 100\r\n" +
                                "\r\n" +
                                "Only 20 bytes here"; // Expected 100, got 19

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req == null;

                report("Test 5: Incomplete Body", pass);
        }

        // ============================================================
        // Test 6: Invalid Method (PUT not supported)
        // ============================================================
        static void test6_InvalidMethod() {
                String raw = "PUT /users/123 HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 6: Invalid Method (PUT)", pass);
        }

        // ============================================================
        // Test 7: Malformed Request Line
        // ============================================================
        static void test7_MalformedRequestLine() {
                String raw = "GET /users\r\n" + // Missing HTTP version
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 7: Malformed Request Line", pass);
        }

        // ============================================================
        // Test 8: POST Without Content-Length
        // ============================================================
        static void test8_POSTWithoutContentLength() {
                String raw = "POST /api/data HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n" +
                                "{\"data\":\"test\"}";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 8: POST Without Content-Length", pass);
        }

        // ============================================================
        // Test 9: GET With Body (invalid)
        // ============================================================
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

        // ============================================================
        // Test 10: Invalid Content-Length (negative)
        // ============================================================
        static void test10_InvalidContentLength() {
                String raw = "POST /api/data HTTP/1.1\r\n" +
                                "Content-Length: -100\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                report("Test 10: Invalid Content-Length", pass);
        }

        // ============================================================
        // Helper: Report Test Result
        // ============================================================
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

/*
 * ============================================================
 * BONUS TESTS - Advanced Scenarios
 * ============================================================
 */
class HTTPParserAdvancedTests {

        public static void runAdvancedTests() {
                System.out.println("\n=== Advanced Tests ===\n");

                testCaseInsensitiveHeaders();
                testMultipleHeadersSameName();
                testURLDecoding();
                testPipelinedRequests();
                testLargeBody();
                testEmptyPOST();
                testHeaderWithoutValue();
                testSpecialCharactersInPath();
                testHTTPVersion10();
                testMaxBodySizeExceeded();
        }

        static void testCaseInsensitiveHeaders() {
                String raw = "GET /test HTTP/1.1\r\n" +
                                "Content-Length: 0\r\n" +
                                "content-length: 0\r\n" + // Duplicate with different case
                                "CONTENT-LENGTH: 0\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                // Should combine all three into one header
                boolean pass = req != null &&
                                req.valid &&
                                req.headers.get("content-length").equals("0, 0, 0");

                System.out.println((pass ? "✅" : "❌") + " Case Insensitive Headers");
        }

        static void testMultipleHeadersSameName() {
                String raw = "GET /test HTTP/1.1\r\n" +
                                "Accept: text/html\r\n" +
                                "Accept: application/json\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.headers.get("accept").equals("text/html, application/json");

                System.out.println((pass ? "✅" : "❌") + " Multiple Headers Same Name");
        }

        static void testURLDecoding() {
                String raw = "GET /search?q=hello%20world&lang=en HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.path.equals("/search?q=hello world&lang=en");

                System.out.println((pass ? "✅" : "❌") + " URL Decoding");
        }

        static void testPipelinedRequests() {
                String raw = "GET /first HTTP/1.1\r\n\r\n" +
                                "GET /second HTTP/1.1\r\n\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.path.equals("/first") &&
                                req.remain.equals("GET /second HTTP/1.1\r\n\r\n");

                System.out.println((pass ? "✅" : "❌") + " Pipelined Requests");
        }

        static void testLargeBody() {
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 1000; i++) {
                        body.append("data");
                }
                String bodyStr = body.toString();

                String raw = "POST /api HTTP/1.1\r\n" +
                                "Content-Length: " + bodyStr.length() + "\r\n" +
                                "\r\n" +
                                bodyStr;

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.body.equals(bodyStr);

                System.out.println((pass ? "✅" : "❌") + " Large Body (4000 bytes)");
        }

        static void testEmptyPOST() {
                String raw = "POST /api HTTP/1.1\r\n" +
                                "Content-Length: 0\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.body.equals("");

                System.out.println((pass ? "✅" : "❌") + " Empty POST Body");
        }

        static void testHeaderWithoutValue() {
                String raw = "GET /test HTTP/1.1\r\n" +
                                "Host:\r\n" + // Empty value
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.headers.get("host").equals("");

                System.out.println((pass ? "✅" : "❌") + " Header Without Value");
        }

        static void testSpecialCharactersInPath() {
                String raw = "GET /path%2Fwith%2Fslashes?key=value%26more HTTP/1.1\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.path.equals("/path/with/slashes?key=value&more");

                System.out.println((pass ? "✅" : "❌") + " Special Characters in Path");
        }

        static void testHTTPVersion10() {
                String raw = "GET /test HTTP/1.0\r\n" +
                                "Host: example.com\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null &&
                                req.valid &&
                                req.version.equals("HTTP/1.0");

                System.out.println((pass ? "✅" : "❌") + " HTTP/1.0 Version");
        }

        static void testMaxBodySizeExceeded() {
                long hugeSize = HTTPParser.CLIENT_MAX_BODY + 1000;
                String raw = "POST /api HTTP/1.1\r\n" +
                                "Content-Length: " + hugeSize + "\r\n" +
                                "\r\n";

                HTTPRequest req = HTTPParser.tryParse(raw);

                boolean pass = req != null && !req.valid;

                System.out.println((pass ? "✅" : "❌") + " Max Body Size Exceeded");
        }
}