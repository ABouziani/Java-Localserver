package configurations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class ConfigLoader {
    public static Config load(String path) {
        try {
            String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            Map<String,Object> m = ConfigParser.parse(text);
            Config c = new Config();
            return c.fromMap(m);
        } catch (Exception e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }
}
