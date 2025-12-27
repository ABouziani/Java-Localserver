package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import routing.Route;
import server.MainServer;
import server.Server;

public class Config {
    public List<String> hosts = new ArrayList<>();
    public List<Listen> listens = new ArrayList<>();
    public int clientMaxBody = 10 * 1024 * 1024; // 10MB default
    public String root = "";
    public Map<String, String> errorPages = new HashMap<>();
    public Map<String, Route> routes = new HashMap<>();
    public Boolean defaultt = false;

    public Config fromMap(Map<?, ?> m) {
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
            this.defaultt = (String) m.get("default") == "true";
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
}