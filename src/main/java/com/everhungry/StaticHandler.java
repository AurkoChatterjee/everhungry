package com.everhungry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Serves the HTML/CSS/JS frontend from the classpath folder /web. */
final class StaticHandler implements HttpHandler {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml",
            "png", "image/png", "ico", "image/x-icon", "json", "application/json");

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        byte[] data = null;
        if (!path.contains("..")) {
            try (InputStream in = StaticHandler.class.getResourceAsStream("/web" + path)) {
                if (in != null) data = in.readAllBytes();
            }
        }
        if (data == null) {
            byte[] msg = "Page not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
