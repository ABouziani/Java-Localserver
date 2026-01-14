package session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SessionManager {
    private final Map<String, Session> sessions = new HashMap<>();

    public Session get(String id) {
        return sessions.get(id);
    }

    public Session create() {
        String id = UUID.randomUUID().toString();
        Session s = new Session(id);
        sessions.put(id, s);
        return s;
    }

    public void cleanup(long timeoutMs) {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(
            s -> now - s.createAt > timeoutMs
        );
    }
}
