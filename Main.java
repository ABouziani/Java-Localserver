import java.io.*;
import java.io.ObjectInputFilter.Config;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;


public class Main {
    public static void main(String[] args) throws Exception {
        String cfgPath = (args.length>0)? args[0] : "config.json";
        Config config = ConfigLoader.load(cfgPath);
        Server server = new Server(config);
        server.start();
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