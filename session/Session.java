package session;

import java.util.HashMap;
import java.util.Map;

public class Session {

    String id;
    public long lastAccessed = System.currentTimeMillis();
    Map<String, String> data = new HashMap<>();

    public Session(String id) {
        this.id = id;
    }
}
