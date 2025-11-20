package configurations;

public class ConfigLoader {
    static Config load(String path) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        // Very small JSON parser to avoid external deps. Assumes config is clean.
        Map<String,Object> m = TinyJson.parse(text);
        return Config.fromMap(m);
    }
}
