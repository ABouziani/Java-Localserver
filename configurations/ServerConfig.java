package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import routing.Route;

public class ServerConfig {
    private String host = "";
    private List<Integer> ports = new ArrayList<>();
    private int clientMaxBody = 10 * 1024 * 1024;
    private String root = "";
    private Map<String, String> errorPages = new HashMap<>();
    private Map<String, Route> routes = new HashMap<>();
    private Boolean isdefault = false;

    public ServerConfig fromMap(Map<?, ?> m) {

        // ===============================
        // CHECK: config map is null or empty
        // ===============================
        if (m == null || m.isEmpty()) {
            System.err.println("[ERROR] Server config is empty or null");
            return null;
        }

        // ===============================
        // CHECK: default server flag
        // ===============================
        if (m.containsKey("default")) {
            Object defaultObj = m.get("default");
            if (defaultObj instanceof Boolean) {
                this.isdefault = (Boolean) defaultObj;
            } else {
                System.err.println("[ERROR] default must be boolean");
                return null;
            }
        }

        // ===============================
        // CHECK: ports
        // ===============================
        if (this.isdefault && m.containsKey("ports")) {
            System.out.println("[WARNING] Default server should not define ports");
        } else if (m.containsKey("ports")) {

            Object o = m.get("ports");
            if (!(o instanceof List)) {
                System.err.println("[ERROR] ports must be a list");
                return null;
            }

            List<?> rawList = (List<?>) o;
            if (rawList.isEmpty()) {
                System.err.println("[ERROR] ports list is empty");
                return null;
            }

            List<Integer> portsList = new ArrayList<>();

            for (Object item : rawList) {
                if (!(item instanceof Number)) {
                    System.err.println("[ERROR] port value is not a number");
                    return null;
                }

                int port = ((Number) item).intValue();

                // CHECK: valid port range
                if (port < 1 || port > 65535) {
                    System.err.println("[ERROR] Port out of range: " + port);
                    return null;
                }

                // CHECK: duplicate port
                if (portsList.contains(port)) {
                    System.err.println("[ERROR] Duplicate port: " + port);
                    return null;
                }

                portsList.add(port);
            }

            this.ports = portsList;

        } else if (!this.isdefault) {
            System.err.println("[ERROR] Non-default server must define ports");
            return null;
        }

        // ===============================
        // CHECK: host
        // ===============================
        if (this.isdefault && m.containsKey("host")) {
            System.out.println("[WARNING] Default server should not define host");
        } else if (m.containsKey("host")) {

            Object hostObj = m.get("host");
            if (!(hostObj instanceof String)) {
                System.err.println("[ERROR] host must be a string");
                return null;
            }

            String h = ((String) hostObj).trim();

            // CHECK: empty host
            if (h.isEmpty()) {
                System.err.println("[ERROR] host is empty");
                return null;
            }
            
            this.host = h;

        } else if (!this.isdefault) {
            System.err.println("[ERROR] Non-default server must define host");
            return null;
        }

        // ===============================
        // CHECK: root
        // ===============================
        if (m.containsKey("root")) {
            Object rootObj = m.get("root");

            if (!(rootObj instanceof String)) {
                System.err.println("[ERROR] root must be a string");
                return null;
            }

            String r = ((String) rootObj).trim();

            // CHECK: empty root path
            if (r.isEmpty()) {
                System.err.println("[ERROR] root path is empty");
                return null;
            }

            this.root = r;
        }

        // ===============================
        // CHECK: clientMaxBody
        // ===============================
        if (m.containsKey("clientMaxBody")) {
            Object bodyObj = m.get("clientMaxBody");

            if (!(bodyObj instanceof Number)) {
                System.err.println("[ERROR] clientMaxBody must be a number");
                return null;
            }

            int size = ((Number) bodyObj).intValue();

            // CHECK: invalid size
            if (size <= 0) {
                System.err.println("[ERROR] clientMaxBody must be > 0");
                return null;
            }

            // CHECK: max allowed size (10MB)
            if (size > 10 * 1024 * 1024) {
                System.err.println("[ERROR] clientMaxBody exceeds 10MB");
                return null;
            }

            this.clientMaxBody = size;
        }

        // ===============================
        // CHECK: error pages
        // ===============================
        if (m.containsKey("errorPages")) {
            Object epObj = m.get("errorPages");

            if (!(epObj instanceof Map)) {
                System.err.println("[ERROR] errorPages must be a map");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> ep = (Map<String, Object>) epObj;

            for (var e : ep.entrySet()) {

                // CHECK: HTTP error code format
                if (!e.getKey().matches("\\d{3}")) {
                    System.err.println("[ERROR] Invalid error code: " + e.getKey());
                    return null;
                }

                if (!(e.getValue() instanceof String)) {
                    System.err.println("[ERROR] Error page path must be string");
                    return null;
                }

                this.errorPages.put(e.getKey(), (String) e.getValue());
            }
        }

        // ===============================
        // CHECK: routes
        // ===============================
        if (m.containsKey("routes")) {
            Object routesObj = m.get("routes");

            if (!(routesObj instanceof Map)) {
                System.err.println("[ERROR] routes must be a map");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> rs = (Map<String, Object>) routesObj;

            // CHECK: empty routes map
            if (rs.isEmpty()) {
                System.err.println("[ERROR] routes section is empty");
                return null;
            }

            for (var en : rs.entrySet()) {

                // CHECK: duplicate route path
                if (this.routes.containsKey(en.getKey())) {
                    System.err.println("[ERROR] Duplicate route: " + en.getKey());
                    return null;
                }

                if (!(en.getValue() instanceof Map)) {
                    System.err.println("[ERROR] Route config must be a map");
                    return null;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> routeMap = (Map<String, Object>) en.getValue();

                Route r = Route.fromMap(routeMap);
                if (r == null) {
                    System.err.println("[ERROR] Invalid route config for: " + en.getKey());
                    return null;
                }

                this.routes.put(en.getKey(), r);
            }
        }

        return this;
    }

    // ===============================
    // Getters & Setters
    // ===============================

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public void setPorts(List<Integer> ports) {
        this.ports = ports;
    }

    public int getClientMaxBody() {
        return clientMaxBody;
    }

    public void setClientMaxBody(int clientMaxBody) {
        this.clientMaxBody = clientMaxBody;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Map<String, String> getErrorPages() {
        return errorPages;
    }

    public void setErrorPages(Map<String, String> errorPages) {
        this.errorPages = errorPages;
    }

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Route> routes) {
        this.routes = routes;
    }

    public Boolean getIsDefault() {
        return isdefault;
    }

    public void setIsDefault(Boolean isdefault) {
        this.isdefault = isdefault;
    }
}
