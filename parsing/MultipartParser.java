package parsing;

public class MultipartParser {

    /**
     * Extracts the body from a multipart/form-data byte array
     */
    public static byte[] extractBody(byte[] data, String boundary) {
        try {
            // Convert boundary to byte array
            byte[] boundaryBytes = ("--" + boundary).getBytes();
            byte[] boundaryEndBytes = ("--" + boundary + "--").getBytes();

            int start = indexOf(data, boundaryBytes);
            if (start == -1) return null;

            // Find the first double CRLF after boundary (headers end)
            int headersEnd = indexOf(data, "\r\n\r\n".getBytes(), start);
            if (headersEnd == -1) return null;

            // The actual body starts after "\r\n\r\n"
            int bodyStart = headersEnd + 4;

            // Find the next boundary to know where the body ends
            int bodyEnd = indexOf(data, boundaryBytes, bodyStart);
            if (bodyEnd == -1) bodyEnd = indexOf(data, boundaryEndBytes, bodyStart);
            if (bodyEnd == -1) bodyEnd = data.length;

            // Trim trailing CRLF before boundary
            if (bodyEnd >= 2 && data[bodyEnd - 2] == '\r' && data[bodyEnd - 1] == '\n') {
                bodyEnd -= 2;
            }

            byte[] body = new byte[bodyEnd - bodyStart];
            System.arraycopy(data, bodyStart, body, 0, body.length);

            return body;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Utility: find the first occurrence of pattern in data starting at fromIndex
     */
    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // Overload: search from beginning
    private static int indexOf(byte[] data, byte[] pattern) {
        return indexOf(data, pattern, 0);
    }

    // Example usage
    public static void main(String[] args) {
        String boundary = "FBqMJG9uoS2cSvD0Z3YyFZ";
        String raw = "--------------------------FBqMJG9uoS2cSvD0Z3YyFZ\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"text.txt\"\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "123456789 10\r\n" +
                "--------------------------FBqMJG9uoS2cSvD0Z3YyFZ--";

        byte[] data = raw.getBytes();
        byte[] body = extractBody(data, boundary);

        System.out.println(new String(body)); // prints: 123456789 10
    }
}
