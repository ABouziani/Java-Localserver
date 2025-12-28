package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import routing.Route;

public class ServerConfig {
    private List<String> hosts = new ArrayList<>();
    private List<Listen> listens = new ArrayList<>();
    private int clientMaxBody = 10 * 1024 * 1024; // 10MB default
    private String root = "";
    private Map<String, String> errorPages = new HashMap<>();
    private Map<String, Route> routes = new HashMap<>();
    private Boolean isdefault;

    
    
    public ServerConfig fromMap(Map<?, ?> m) {
        if (m.containsKey("listen")) {
            Object o = m.get("listen");
            if (o instanceof List) {
                List<?> rawList = (List<?>) o;
                List<Listen> listensList = new ArrayList<>();

                for (Object item : rawList) {
                    if (item instanceof Map) {
                        Map<?, ?> mapItem = (Map<?, ?>) item;
                        String ip = (String) mapItem.get("ip");
                        int port = ((Number) mapItem.get("port")).intValue();
                        listensList.add(new Listen(ip, port));
                    }
                }

                this.listens = listensList;
            }
        }
        if (m.containsKey("hosts")) {
            Object o = m.get("hosts");
            if (o instanceof List) {
                List<?> rawList = (List<?>) o;
                List<String> hostList = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof String) {
                        hostList.add((String) item);
                    }
                }
                this.hosts = hostList;
            }
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
    public List<String> getHosts() { return hosts; }
    public void setHosts(List<String> hosts) { this.hosts = hosts; }
    
    public List<Listen> getListens() { return listens; }
    public void setListens(List<Listen> listens) { this.listens = listens; }
    
    public int getClientMaxBody() { return clientMaxBody; }
    public void setClientMaxBody(int clientMaxBody) { this.clientMaxBody = clientMaxBody; }
    
    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
    
    public Map<String, String> getErrorPages() { return errorPages; }
    public void setErrorPages(Map<String, String> errorPages) { this.errorPages = errorPages; }
    
    public Map<String, Route> getRoutes() { return routes; }
    public void setRoutes(Map<String, Route> routes) { this.routes = routes; }
    
    public Boolean getIsDefault() { return isdefault; }
    public void setIsDefaultt(Boolean defaultt) { this.isdefault = defaultt; }
}