package session;

public class Session {

    String id;
    long lastAccessed = System.currentTimeMillis();
    Map<String, String> data = new HashMap<>();

    Session(String id) {
        this.id = id;
    }
}
