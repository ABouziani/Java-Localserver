package configurations;

public class Config {
    public List<Integer> ports = List.of(8080);
    public String host = "0.0.0.0";
    public String root = "www";
    public Map<String,String> errorPages = new HashMap<>();
    public int clientMaxBody = 10 * 1024 * 1024; // 10MB default
    public Map<String,Route> routes = new HashMap<>();

    static Config fromMap(Map<String,Object> m) {
        Config c = new Config();
        if (m.containsKey("ports")) {
            ArrayList<Object> a = (ArrayList<Object>) m.get("ports");
            c.ports = new ArrayList<>();
            for (Object o: a) c.ports.add(((Number)o).intValue());
        }
        if (m.containsKey("host")) c.host = (String)m.get("host");
        if (m.containsKey("root")) c.root = (String)m.get("root");
        if (m.containsKey("clientMaxBody")) c.clientMaxBody = ((Number)m.get("clientMaxBody")).intValue();
        if (m.containsKey("errorPages")) {
            Map<String,Object> ep = (Map<String,Object>) m.get("errorPages");
            for (var e: ep.entrySet()) c.errorPages.put(e.getKey(), (String)e.getValue());
        }
        if (m.containsKey("routes")) {
            Map<String,Object> rs = (Map<String,Object>) m.get("routes");
            for (var en: rs.entrySet()) {
                c.routes.put(en.getKey(), Route.fromMap((Map<String,Object>)en.getValue()));
            }
        }
        return c;
    }
}