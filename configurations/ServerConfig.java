package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import routing.Route;

public class ServerConfig {
    private String host = new String();
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
            this.host = (String) m.get("hosts");
        }
        if (m.containsKey("root"))
            this.root = (String) m.get("root");
        if (m.containsKey("default"))
            this.isdefault = (String) m.get("default") == "true";
        if (m.containsKey("clientMaxBody"))
            this.clientMaxBody = ((Number) m.get("clientMaxBody")).intValue();
        if (m.containsKey("errorPages")) {
            Map<String, Object> ep = (Map<String, Object>) m.get("errorPages");
            for (var e : ep.entrySet())
                this.errorPages.put(e.getKey(), (String) e.getValue());
        }
        if (m.containsKey("routes")) {
            Map<String, Object> rs = (Map<String, Object>) m.get("routes");
            for (var en : rs.entrySet()) {
                this.routes.put(en.getKey(), Route.fromMap((Map<String, Object>) en.getValue()));
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

    public void setIsDefaultt(Boolean defaultt) {
        this.isdefault = defaultt;
    }
}