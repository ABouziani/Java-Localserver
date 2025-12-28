package configurations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConfigLoader {
    public static List<ServerConfig> load(String path) {
        try {
            String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            Map<String, Object> m = ConfigParser.parse(text);
            // System.out.println(m);
            // System.out.println("-------");
            if (m.containsKey("servers")) {
                Object o = m.get("servers");
                if (o instanceof List) {
                    List<?> rawList = (List<?>) o;
                    List<ServerConfig> cfgs = new ArrayList<ServerConfig>();
                    for (Object item : rawList) {
                        
                        if (item instanceof Map) {
                            Map<String,Object> map = (Map<String,Object>) item; 
                            cfgs.add(new ServerConfig().fromMap(map));
                        }
                        System.out.println("----------------");
                    }
                    return cfgs;
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }
}
