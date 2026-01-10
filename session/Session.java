package session;

public class Session {

    String id;
    public long createAt = System.currentTimeMillis();

    public Session(String id) {
        this.id = id;
    }
}
