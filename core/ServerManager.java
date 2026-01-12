package core;

import configurations.Config;
import configurations.ServerConfig;
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
            System.out.println(key);
            if (!boundAddresses.contains(key)) {
                boundAddresses.add(key);
                addressToServer.put(key, server);
                Set<Integer> dup = new HashSet<>();
                for (Integer port : server.getPorts()) {
                    if (dup.contains(port)) {
                        System.err.println("[WARNING] Server: " + server.getHost() + " duplicated ports will be ignored!" );
                    }
                    dup.add(port);
                    // Only bind once per unique ip:port
                    if (!portscreated.contains(port)){
                        ServerSocketChannel serverChannel = ServerSocketChannel.open();
                        serverChannel.configureBlocking(false);
                        serverChannel.bind(new InetSocketAddress(port),1024);
                        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                        portscreated.add(port);
                    }
                }
            } else {
                throw new IllegalArgumentException(
                        "Duplicate host address: " + key +
                                " - each host must be unique to one server");
            }
        }
        if (portscreated.size() == 0) {
            System.out.println("Error: No valid port binded, check your config!");
            System.exit(1);
        }
    }

    public VirtualServer getServer(String host) {
        
        System.out.println("--s-s-s-s-s");
        System.out.println(host);
        VirtualServer server = addressToServer.get(host);

        if (server == null) {
            //System.err.println("Warning: No server found for address " + host +
                    //", using default");

            return defaultServer;
        }
        return server;
    }
}
