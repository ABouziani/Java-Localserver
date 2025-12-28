package configurations;


public class Listen {
    private String ip;    
    private int port;     
    
    public Listen() {}
    
    public Listen(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }
    
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    
    @Override
    public String toString() {
        return ip + ":" + port;
    }
}