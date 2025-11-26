package configurations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class ConfigLoader {
    public static Config load(String path) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        // Very small JSON parser to avoid external deps. Assumes config is clean.
        Map<String,Object> m = ConfigParser.parse(text);
        return Config.fromMap(m);
    }
}
