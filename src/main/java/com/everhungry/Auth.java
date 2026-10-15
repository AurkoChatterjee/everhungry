package com.everhungry;

import com.sun.net.httpserver.HttpExchange;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Password hashing (PBKDF2) and cookie sessions held in memory. */
public final class Auth {
    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String COOKIE = "eh_session";

    private Auth() {}

    static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(password, salt));
    }

    static boolean verify(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        return MessageDigest.isEqual(expected, pbkdf2(password, Base64.getDecoder().decode(parts[0])));
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void startSession(HttpExchange ex, long userId) {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        SESSIONS.put(token, userId);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800");
    }

    static void endSession(HttpExchange ex) {
        String token = token(ex);
        if (token != null) SESSIONS.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; Max-Age=0");
    }

    private static String token(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(COOKIE)) return kv[1];
            }
        }
        return null;
    }

    /** The signed-in user (without password hash), or null. */
    static Map<String, Object> current(HttpExchange ex) {
        String token = token(ex);
        if (token == null) return null;
        Long id = SESSIONS.get(token);
        if (id == null) return null;
        Map<String, Object> user = Db.one("SELECT id, name, email, role, phone, suspended FROM users WHERE id = ?", id);
        if (user == null || Db.bool(user.get("suspended"))) return null;
        return user;
    }
}
