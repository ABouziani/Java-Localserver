import java.util.ArrayList;
import java.util.List;

import configurations.ServerConfig;
import configurations.Config;
import configurations.ConfigLoader;
import core.MainServer;
import core.VirtualServer;


public class Main {
    public static void main(String[] args) throws Exception {
        String cfgPath = (args.length>0)? args[0] : "config.json";
        List<ServerConfig> Listconfigs = ConfigLoader.load(cfgPath);
        List<VirtualServer> servers = new ArrayList<VirtualServer>();
        System.out.println("+++++++");
        MainServer mainServer = new MainServer(new Config(Listconfigs));
        for (ServerConfig config: Listconfigs){
          VirtualServer server = new VirtualServer(config,mainServer);
          // if (server.gte().defaultt) {
          //   MainServer.getDefaultt() = server;
          // }
          servers.add(server);
        }
        mainServer.start();
    }
}

/* ------------------ error pages and README (examples) ------------------ */
// config.json (example) -- create next to jar
/*
{
  "ports": [8080, 8081],
  "host": "0.0.0.0",
  "root": "www",
  "clientMaxBody": 10485760,
  "errorPages": {"404":"error_pages/404.html","500":"error_pages/500.html"},
  "routes": {
    "/": {"methods":["GET","POST","DELETE"], "root":"www", "index":"index.html", "dirList":true, "cgi":{"py":"python3"}}
  }
}
*/

// README: Build and run instructions
/*
1) javac -d out LocalServer.java
2) mkdir www && mkdir error_pages
3) create config.json as example above
4) java -cp out Main config.json
*/