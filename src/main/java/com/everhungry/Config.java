package com.everhungry;

import java.io.InputStream;
import java.util.Properties;

/** Reads application.properties; environment variables win when set. */
public final class Config {
    private static final Properties P = new Properties();

    private Config() {}

    static void load() {
        try (InputStream in = Config.class.getResourceAsStream("/application.properties")) {
            if (in != null) P.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read application.properties", e);
        }
    }

    private static String get(String key, String env, String def) {
        String v = System.getenv(env);
        return (v != null && !v.isBlank()) ? v : P.getProperty(key, def);
    }

    static int port()            { return Integer.parseInt(get("server.port", "PORT", "8080")); }
    static String dbUrl()        { return get("db.url", "DB_URL", "jdbc:mysql://localhost:3306/everhungry?createDatabaseIfNotExist=true"); }
    static String dbUser()       { return get("db.user", "DB_USER", "root"); }
    static String dbPassword()   { return get("db.password", "DB_PASSWORD", ""); }
    static double deliveryFee()  { return Double.parseDouble(get("order.deliveryFee", "DELIVERY_FEE", "30")); }
}
