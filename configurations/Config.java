package configurations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Config {
    private Map<String, String> globalErrorPages;  
    private List<ServerConfig> servers;            
    
    public Config() {
        this.globalErrorPages = new HashMap<>();
        this.servers = new ArrayList<>();
    }
    public Config(List<ServerConfig> servers) {
        this.globalErrorPages = new HashMap<>();
        this.servers = servers;
    }
    
    public Map<String, String> getGlobalErrorPages() {
        return globalErrorPages;
    }
    
    public void setGlobalErrorPages(Map<String, String> globalErrorPages) {
        this.globalErrorPages = globalErrorPages;
    }
    
    public List<ServerConfig> getServers() {
        return servers;
    }
    
    public void setServers(List<ServerConfig> servers) {
        this.servers = servers;
    }
}