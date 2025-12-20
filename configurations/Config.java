package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import routing.Route;

public class Config {
    public List<Integer> ports = List.of(8080);
    public String host = "0.0.0.0";
    public String root = "www";
    public Map<String,String> errorPages = new HashMap<>();
    public int clientMaxBody = 10 * 1024 * 1024; // 10MB default
    public Map<String,Route> routes = new HashMap<>();
    public Config fromMap(Map<String,Object> m) {
        if (m.containsKey("ports")) {
            ArrayList<Object> a = (ArrayList<Object>) m.get("ports");
            this.ports = new ArrayList<>();
            for (Object o: a) this.ports.add(((Number)o).intValue());
        }
        if (m.containsKey("host")) this.host = (String)m.get("host");
        if (m.containsKey("root")) this.root = (String)m.get("root");
        if (m.containsKey("clientMaxBody")) this.clientMaxBody = ((Number)m.get("clientMaxBody")).intValue();
        if (m.containsKey("errorPages")) {
            Map<String,Object> ep = (Map<String,Object>) m.get("errorPages");
            for (var e: ep.entrySet()) this.errorPages.put(e.getKey(), (String)e.getValue());
        }
        if (m.containsKey("routes")) {
            Map<String,Object> rs = (Map<String,Object>) m.get("routes");
            for (var en: rs.entrySet()) {
                this.routes.put(en.getKey(), Route.fromMap((Map<String,Object>)en.getValue()));
            }
        }
        return this;
    }
}