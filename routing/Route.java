package routing;

public class Route {
    public Set<String> methods = new HashSet<>();
    public String root = null;
    public String index = "index.html";
    public boolean dirList = false;
    public Map<String,String> cgi = new HashMap<>();
    public String redirect = null;

    static Route fromMap(Map<String,Object> m) {
        Route r = new Route();
        if (m.containsKey("methods")) {
            ArrayList<Object> a = (ArrayList<Object>) m.get("methods");
            for (Object o: a) r.methods.add(((String)o).toUpperCase());
        }
        if (m.containsKey("root")) r.root = (String)m.get("root");
        if (m.containsKey("index")) r.index = (String)m.get("index");
        if (m.containsKey("dirList")) r.dirList = (Boolean)m.get("dirList");
        if (m.containsKey("cgi")) {
            Map<String,Object> cc = (Map<String,Object>) m.get("cgi");
            for (var e: cc.entrySet()) r.cgi.put(e.getKey(), (String)e.getValue());
        }
        if (m.containsKey("redirect")) r.redirect = (String)m.get("redirect");
        return r;
    }
}