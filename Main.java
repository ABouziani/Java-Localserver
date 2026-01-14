import configurations.Config;
import configurations.ConfigLoader;
import configurations.ServerConfig;
import core.MainServer;
import java.util.List;

public class Main {
  public static void main(String[] args) {
    try {
      String cfgPath = (args.length > 0) ? args[0] : "config.json";
      List<ServerConfig> Listconfigs = ConfigLoader.load(cfgPath);
      if (Listconfigs == null || Listconfigs.isEmpty()) {
        System.err.println("Error: No valid server found, check your config!");
        System.exit(1);
      }
      MainServer mainServer = new MainServer(new Config(Listconfigs));
      mainServer.start();

    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }
}
