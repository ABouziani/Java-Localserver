package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import routing.Route;

public class ServerConfig {
    private String host = "";
    private List<Integer> ports = new ArrayList<>();
    private int clientMaxBody = 10 * 1024 * 1024; // 10MB default
    private String root = "";
    private Map<String, String> errorPages = new HashMap<>();
    private Map<String, Route> routes = new HashMap<>();
    private Boolean isdefault = false;

    public ServerConfig fromMap(Map<?, ?> m) {
        if (m.containsKey("ports")) {
            Object o = m.get("ports");
            if (o instanceof List) {
                List<?> rawList = (List<?>) o;
                List<Integer> portsList = new ArrayList<>();

                for (Object item : rawList) {
                    if (item instanceof Number) {
                        int port = ((Number) item).intValue();
                        portsList.add(port);
                    }
                }

                this.ports = portsList;
            }
        }

        if (m.containsKey("host")) {
            Object hostObj = m.get("host");
            if (hostObj instanceof String) {
                this.host = (String) hostObj;
            }
        }

        if (m.containsKey("root")) {
            Object rootObj = m.get("root");
            if (rootObj instanceof String) {
                this.root = (String) rootObj;
            }
        }

        if (m.containsKey("default")) {
            Object defaultObj = m.get("default");
            if (defaultObj instanceof Boolean) {
                this.isdefault = (Boolean) defaultObj;
            }
        }

        if (m.containsKey("clientMaxBody")) {
            Object bodyObj = m.get("clientMaxBody");
            if (bodyObj instanceof Number) {
                this.clientMaxBody = ((Number) bodyObj).intValue();
            }
        }

        if (m.containsKey("errorPages")) {
            Object epObj = m.get("errorPages");
            if (epObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ep = (Map<String, Object>) epObj;
                for (var e : ep.entrySet()) {
                    if (e.getValue() instanceof String) {
                        this.errorPages.put(e.getKey(), (String) e.getValue());
                    }
                }
            }
        }

        if (m.containsKey("routes")) {
            Object routesObj = m.get("routes");
            if (routesObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rs = (Map<String, Object>) routesObj;
                for (var en : rs.entrySet()) {
                    if (en.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> routeMap = (Map<String, Object>) en.getValue();
                        this.routes.put(en.getKey(), Route.fromMap(routeMap));
                    }
                }
            }
        }

        return this;
    }

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