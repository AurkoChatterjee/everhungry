package com.everhungry;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        Config.load();
        Schema.init();

        HttpServer server = HttpServer.create(new InetSocketAddress(Config.port()), 0);
        server.createContext("/api/", new Api());
        server.createContext("/", new StaticHandler());
        // One thread per request: open event streams hold a thread each.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log.info("EverHungry is running at http://localhost:{}", Config.port());
    }
}
