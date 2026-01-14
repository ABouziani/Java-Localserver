package configurations;

public final class HostValidator {

    private HostValidator() {}

    public static boolean isValidHostHeader(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }

        // Reject spaces or path characters
        if (containsInvalidChars(host)) {
            return false;
        }

        String hostname;
        String portPart = null;

        // IPv6: [::1]:8080
        if (host.charAt(0) == '[') {
            int close = host.indexOf(']');
            if (close == -1) return false;

            hostname = host.substring(1, close);

            if (close + 1 < host.length()) {
                if (host.charAt(close + 1) != ':') return false;
                portPart = host.substring(close + 2);
            }

            if (!isValidIPv6(hostname)) return false;
        } else {
            int colon = host.indexOf(':');
            if (colon != -1) {
                hostname = host.substring(0, colon);
                portPart = host.substring(colon + 1);
            } else {
                hostname = host;
            }

            if (!isValidHostnameOrIPv4(hostname)) {
                return false;
            }
        }

        // Validate port if present
        if (portPart != null && !isValidPort(portPart)) {
            return false;
        }

        return true;
    }

    private static boolean containsInvalidChars(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == ' ' || c == '/' || c == '\\') {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidPort(String port) {
        if (port.isEmpty()) return false;

        int value = 0;
        for (int i = 0; i < port.length(); i++) {
            char c = port.charAt(i);
            if (c < '0' || c > '9') return false;
            value = value * 10 + (c - '0');
            if (value > 65535) return false;
        }

        return value > 0;
    }

    private static boolean isValidHostnameOrIPv4(String host) {
        if (host.isEmpty()) return false;

        boolean hasLetter = false;

        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);

            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c) || c == '-' || c == '.') {
                // allowed
            } else {
                return false;
            }
        }

        // Prevent leading/trailing dot
        if (host.charAt(0) == '.' || host.charAt(host.length() - 1) == '.') {
            return false;
        }

        // If no letters → likely IPv4
        if (!hasLetter) {
            return isValidIPv4(host);
        }

        return true;
    }

    private static boolean isValidIPv4(String ip) {
        String[] parts = ip.split("\\.", -1); // allowed here only for split
        if (parts.length != 4) return false;

        for (String part : parts) {
            if (part.isEmpty()) return false;

            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
                value = value * 10 + (c - '0');
            }

            if (value > 255) return false;
        }

        return true;
    }

    private static boolean isValidIPv6(String ip) {
        // Minimal IPv6 validation (project-safe)
        if (ip.isEmpty()) return false;

        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (Character.digit(c, 16) != -1 || c == ':') {
                continue;
            }
            return false;
        }

        return true;
    }
}
