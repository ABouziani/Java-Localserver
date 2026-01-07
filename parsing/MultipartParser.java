package parsing;

import java.io.ByteArrayOutputStream;

public class MultipartParser {

    

public static byte[] extractBody(byte[] data) {

    // 1) find first line (boundary)
    int firstLineEnd = indexOf(data, "\r\n".getBytes(), 0);
    if (firstLineEnd == -1) return null;

    // boundary bytes include the leading --
    byte[] boundary = new byte[firstLineEnd];
    System.arraycopy(data, 0, boundary, 0, firstLineEnd);

    // 2) find end of headers (\r\n\r\n)
    int headersEnd = indexOf(data, "\r\n\r\n".getBytes(), firstLineEnd + 2);
    if (headersEnd == -1) return null;

    int bodyStart = headersEnd + 4; // skip CRLFCRLF

    // 3) find next boundary after body start
    int bodyEnd = indexOf(data, boundary, bodyStart);
    if (bodyEnd == -1) bodyEnd = data.length;

    // trim \r\n before boundary
    if (bodyEnd >= 2 && data[bodyEnd - 2] == '\r' && data[bodyEnd - 1] == '\n') {
        bodyEnd -= 2;
    }

    // 4) copy body bytes
    byte[] result = new byte[bodyEnd - bodyStart];
    System.arraycopy(data, bodyStart, result, 0, result.length);

    return result;
}
private static int indexOf(byte[] data, byte[] pattern, int from) {
    outer:
    for (int i = from; i <= data.length - pattern.length; i++) {
        for (int j = 0; j < pattern.length; j++) {
            if (data[i + j] != pattern[j]) continue outer;
        }
        return i;
    }
    return -1;
}

private static int indexOf(byte[] data, byte[] pattern) {
    return indexOf(data, pattern, 0);
}


}
