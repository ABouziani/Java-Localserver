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
    private final Map<String, VirtualServer> addressToServer = new HashMap<>();
    private VirtualServer defaultServer;
    
    public ServerManager(Config config, MainServer mainServer) {
        for (ServerConfig serverCfg : config.getServers()) {
            VirtualServer vs = new VirtualServer(serverCfg, mainServer);
            virtualServers.add(vs);
            
            // for (String host : serverCfg.getHosts()) {
            //     addressToServer.put(host.toLowerCase(), vs);
            // }
            
            if (serverCfg.getIsDefault()) {
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
                    addressToServer.put(key, server);
                    ServerSocketChannel serverChannel = ServerSocketChannel.open();
                    serverChannel.configureBlocking(false);
                    serverChannel.bind(new InetSocketAddress(listen.getIp(), listen.getPort()));
                    serverChannel.register(selector, SelectionKey.OP_ACCEPT,key);
                    boundAddresses.add(key);
                }else{
                    throw new IllegalArgumentException(
                        "Duplicate listen address: " + key + 
                        " - each ip:port must be unique to one server"
                    );
                }
            }
        }
    }
    
   public VirtualServer getServer(String listenAddress) {
        VirtualServer server = addressToServer.get(listenAddress);
        
        if (server == null) {
            System.err.println("Warning: No server found for address " + listenAddress + 
                             ", using default");
            return defaultServer;
        }
        
        return server;
    }
}

