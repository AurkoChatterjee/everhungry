package com.everhungry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Server-sent events: pushes order and location changes to the people involved. */
public final class Events {
    private record Sub(long userId, String role, OutputStream out) {}

    private static final List<Sub> SUBS = new CopyOnWriteArrayList<>();

    static {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> write(s -> true, ": ping\n\n"), 20, 20, TimeUnit.SECONDS);
    }

    private Events() {}

    static void subscribe(long userId, String role, OutputStream out) {
        SUBS.add(new Sub(userId, role, out));
    }

    static void toUser(long userId, Map<String, Object> message) {
        write(s -> s.userId() == userId, "data: " + Json.str(message) + "\n\n");
    }

    static void toRole(String role, Map<String, Object> message) {
        write(s -> s.role().equals(role), "data: " + Json.str(message) + "\n\n");
    }

    /** Tell the customer, the restaurant owner and the driver (or all drivers, if unassigned) that an order changed. */
    static void orderChanged(long orderId) {
        Map<String, Object> o = Db.one(
                "SELECT o.customer_id, o.driver_id, o.status, r.owner_id FROM orders o "
                        + "JOIN restaurants r ON r.id = o.restaurant_id WHERE o.id = ?", orderId);
        if (o == null) return;
        Map<String, Object> msg = Map.of("type", "order", "orderId", orderId, "status", o.get("status"));
        toUser(Db.num(o.get("customer_id")), msg);
        toUser(Db.num(o.get("owner_id")), msg);
        toRole("admin", msg);
        if (o.get("driver_id") != null) toUser(Db.num(o.get("driver_id")), msg);
        else toRole("driver", Map.of("type", "available"));
    }

    private static void write(Predicate<Sub> filter, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        for (Sub s : SUBS) {
            if (!filter.test(s)) continue;
            try {
                synchronized (s) {
                    s.out().write(bytes);
                    s.out().flush();
                }
            } catch (IOException | RuntimeException e) {
                SUBS.remove(s);
                try { s.out().close(); } catch (IOException ignored) { }
            }
        }
    }
}
