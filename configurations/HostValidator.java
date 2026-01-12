package configurations;

import java.util.regex.Pattern;

public final class HostValidator {

    // RFC-compliant (practical) patterns
    private static final Pattern DOMAIN =
            Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    private static final Pattern IPV4 =
            Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.)){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private static final Pattern IPV6 =
            Pattern.compile("^\\[[0-9a-fA-F:]+\\]$");

    private static final Pattern PORT =
            Pattern.compile("^:[0-9]{1,5}$");


    public static boolean isValidHostHeader(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }

        // No spaces, no scheme, no path
        if (host.contains(" ") || host.contains("/") || host.contains("://")) {
            return false;
        }

        String hostname;
        String port = null;

        // IPv6 case: [addr]:port?
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end == -1) return false;

            hostname = host.substring(0, end + 1);
            if (!IPV6.matcher(hostname).matches()) {
                return false;
            }

            if (host.length() > end + 1) {
                port = host.substring(end + 1);
            }
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                hostname = host.substring(0, colon);
                port = host.substring(colon);
            } else {
                hostname = host;
            }

            if (!DOMAIN.matcher(hostname).matches()
                    && !IPV4.matcher(hostname).matches()) {
                return false;
            }
        }

        // Validate port if present
        if (port != null) {
            if (!PORT.matcher(port).matches()) return false;

            int p = Integer.parseInt(port.substring(1));
            if (p < 1 || p > 65535) return false;
        }

        return true;
    }
}

