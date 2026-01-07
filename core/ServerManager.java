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
import configurations.ServerConfig;

public class ServerManager {
    private final List<VirtualServer> virtualServers = new ArrayList<>();
    private final Map<String, VirtualServer> addressToServer = new HashMap<>();
    private VirtualServer defaultServer;

    public ServerManager(Config config, MainServer mainServer) {
        for (ServerConfig serverCfg : config.getServers()) {
            VirtualServer vs = new VirtualServer(serverCfg, mainServer);
            virtualServers.add(vs);

            if (serverCfg.getIsDefault() && defaultServer == null) {
                defaultServer = vs;
            } else if (serverCfg.getIsDefault() && defaultServer != null) {
                throw new IllegalArgumentException(
                        "Multiple default servers defined; only one server can be default");
            }
        }
    }

    public void bindAllListeners(Selector selector) throws IOException {
        Set<String> boundAddresses = new HashSet<>();
        Set<Integer> portscreated = new HashSet<>();
        for (VirtualServer server : virtualServers) {
            String key = server.getHost();
            if (!boundAddresses.contains(key)) {
                boundAddresses.add(key);
                addressToServer.put(key, server);
                for (Integer port : server.getPorts()) {
                    // Only bind once per unique ip:port
                    if (!portscreated.contains(port)){
                        portscreated.add(port);
                        ServerSocketChannel serverChannel = ServerSocketChannel.open();
                        serverChannel.configureBlocking(false);
                        serverChannel.bind(new InetSocketAddress(port));
                        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                    }
                }
            } else {
                throw new IllegalArgumentException(
                        "Duplicate host address: " + key +
                                " - each host must be unique to one server");
            }
        }
    }

    public VirtualServer getServer(String host) {
        
        
        VirtualServer server = addressToServer.get(host);

        if (server == null) {
            // System.err.println("Warning: No server found for address " + host +
            //         ", using default");

            return defaultServer;
        }
        return server;
    }
}
