package core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import configurations.Config;
import configurations.Listen;
import configurations.ServerConfig;

public class ServerManager {
    private final List<VirtualServer> virtualServers = new ArrayList<>();
    private final Map<String, VirtualServer> hostToServer = new HashMap<>();
    private VirtualServer defaultServer;
    
    public ServerManager(Config config, MainServer mainServer) {
        for (ServerConfig serverCfg : config.getServers()) {
            VirtualServer vs = new VirtualServer(serverCfg, mainServer);
            virtualServers.add(vs);
            
            for (String host : serverCfg.getHosts()) {
                hostToServer.put(host.toLowerCase(), vs);
            }
            
            if (serverCfg.getDefaultt()) {
                defaultServer = vs;
            }
        }
    }
    
    public void bindAllListeners(Selector selector) throws IOException {
        Set<String> boundAddresses = new HashSet<>();
        
        for (VirtualServer server : virtualServers) {
            for (Listen listen : server.getListens()) {
                String key = listen.getIp() + ":" + listen.getPort();
                
                // Only bind once per unique ip:port
                if (!boundAddresses.contains(key)) {
                    ServerSocketChannel serverChannel = ServerSocketChannel.open();
                    serverChannel.configureBlocking(false);
                    serverChannel.bind(new InetSocketAddress(listen.getIp(), listen.getPort()));
                    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                    boundAddresses.add(key);
                }
            }
        }
    }
    
    public VirtualServer getServerForHost(String host) {
        if (host == null) return defaultServer;
        
        VirtualServer server = hostToServer.get(host.toLowerCase());
        return server != null ? server : defaultServer;
    }
}