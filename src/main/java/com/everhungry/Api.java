package com.everhungry;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All REST endpoints under /api. */
final class Api implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(Api.class);
    private static final Object STREAM = new Object();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final double DEFAULT_LAT = 23.2599, DEFAULT_LNG = 77.4126;

    private interface Fn { Object run(Ctx c) throws Exception; }

    private record Route(String method, Pattern pattern, List<String> names, String role, Fn fn) {}

    private final List<Route> routes = new ArrayList<>();

    Api() {
        // auth
        route("POST", "/api/auth/register", null, this::register);
        route("POST", "/api/auth/login", null, this::login);
        route("POST", "/api/auth/logout", null, c -> { Auth.endSession(c.ex); return Map.of("ok", true); });
        route("GET", "/api/me", null, c -> {
            Map<String, Object> out = new HashMap<>();
            out.put("user", c.user);
            return out;
        });

        // browsing
        route("GET", "/api/restaurants", null, c -> Db.query(
                "SELECT r.id, r.name, r.cuisine, r.description, r.address, r.lat, r.lng, r.is_open, r.prep_minutes, "
                        + "(SELECT COUNT(*) FROM menu_items m WHERE m.restaurant_id = r.id AND m.available) AS item_count "
                        + "FROM restaurants r ORDER BY r.is_open DESC, r.name"));
        route("GET", "/api/restaurants/{id}", null, c -> {
            Map<String, Object> r = Db.one("SELECT id, name, cuisine, description, address, lat, lng, is_open, prep_minutes "
                    + "FROM restaurants WHERE id = ?", c.id("id"));
            if (r == null) throw new ApiException(404, "Restaurant not found");
            r.put("menu", Db.query("SELECT id, name, description, price FROM menu_items "
                    + "WHERE restaurant_id = ? AND available ORDER BY id", c.id("id")));
            r.put("delivery_fee", Config.deliveryFee());
            return r;
        });

        // restaurant owner
        route("GET", "/api/owner/restaurant", "owner", c -> {
            Map<String, Object> r = ownRestaurant(c);
            r.put("menu", Db.query("SELECT id, name, description, price, available FROM menu_items "
                    + "WHERE restaurant_id = ? ORDER BY id", r.get("id")));
            return r;
        });
        route("PUT", "/api/owner/restaurant", "owner", this::updateRestaurant);
        route("POST", "/api/owner/menu", "owner", this::addMenuItem);
        route("PUT", "/api/owner/menu/{id}", "owner", this::updateMenuItem);
        route("DELETE", "/api/owner/menu/{id}", "owner", c -> {
            ownMenuItem(c);
            Db.update("DELETE FROM menu_items WHERE id = ?", c.id("id"));
            return Map.of("ok", true);
        });

        // orders
        route("POST", "/api/orders", "customer", this::createOrder);
        route("GET", "/api/orders", "*", this::listOrders);
        route("GET", "/api/orders/{id}", "*", c -> {
            Map<String, Object> o = orderView(c.id("id"));
            if (o == null || !canView(o, c)) throw new ApiException(404, "Order not found");
            return o;
        });
        route("POST", "/api/orders/{id}/status", "*", this::changeStatus);
        route("POST", "/api/orders/{id}/claim", "driver", this::claimOrder);

        // driver
        route("GET", "/api/driver/available", "driver", c -> {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> row : Db.query("SELECT id FROM orders WHERE driver_id IS NULL "
                    + "AND status IN ('ACCEPTED','PREPARING','READY') ORDER BY id")) {
                out.add(orderView(Db.num(row.get("id"))));
            }
            return out;
        });
        route("POST", "/api/driver/location", "driver", this::saveLocation);

        // admin
        route("GET", "/api/admin/stats", "admin", c -> adminStats());
        route("GET", "/api/admin/orders", "admin", c -> {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> row : Db.query("SELECT id FROM orders ORDER BY id DESC LIMIT 100")) out.add(orderView(Db.num(row.get("id"))));
            return out;
        });
        route("GET", "/api/admin/users", "admin", c -> Db.query(
                "SELECT id, name, email, role, phone, suspended, created_at FROM users ORDER BY id"));
        route("POST", "/api/admin/users/{id}/suspend", "admin", this::suspendUser);
        route("GET", "/api/admin/restaurants", "admin", c -> Db.query(
                "SELECT r.id, r.name, r.cuisine, r.is_open, u.name AS owner_name, u.email AS owner_email, "
                        + "(SELECT COUNT(*) FROM orders o WHERE o.restaurant_id = r.id) AS order_count "
                        + "FROM restaurants r JOIN users u ON u.id = r.owner_id ORDER BY r.id"));
        route("POST", "/api/admin/restaurants/{id}/open", "admin", c -> {
            if (Db.update("UPDATE restaurants SET is_open = ? WHERE id = ?", Boolean.TRUE.equals(c.bool("open")), c.id("id")) == 0 
                    && Db.one("SELECT id FROM restaurants WHERE id = ?", c.id("id")) == null) throw new ApiException(404, "Restaurant not found");
            return Map.of("ok", true);
        });

        // live updates
        route("GET", "/api/events", "*", c -> {
            HttpExchange ex = c.ex;
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            Events.subscribe(c.uid(), c.role(), os);
            return STREAM;
        });
    }

    // ---------------------------------------------------------------- routing

    private void route(String method, String template, String role, Fn fn) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(template);
        StringBuilder regex = new StringBuilder();
        while (m.find()) {
            names.add(m.group(1));
            m.appendReplacement(regex, "(\\\\d+)");
        }
        m.appendTail(regex);
        routes.add(new Route(method, Pattern.compile(regex.toString()), names, role, fn));
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            for (Route r : routes) {
                Matcher m = r.pattern().matcher(path);
                if (!r.method().equals(ex.getRequestMethod()) || !m.matches()) continue;
                Ctx c = new Ctx(ex);
                for (int i = 0; i < r.names().size(); i++) c.params.put(r.names().get(i), m.group(i + 1));
                c.user = Auth.current(ex);
                if (r.role() != null) {
                    if (c.user == null) throw new ApiException(401, "Please log in first");
                    if (!r.role().equals("*") && !r.role().equals(c.role())) {
                        throw new ApiException(403, "This action is not available for your account type");
                    }
                }
                Object out = r.fn().run(c);
                if (out != STREAM) send(ex, 200, out);
                return;
            }
            send(ex, 404, Map.of("error", "Not found"));
        } catch (ApiException e) {
            send(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Request failed: {} {}", ex.getRequestMethod(), ex.getRequestURI(), e);
            send(ex, 500, Map.of("error", "Something went wrong on our side. Try again."));
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.str(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ------------------------------------------------------------------- auth

    private Object register(Ctx c) throws Exception {
        String name = c.text("name"), email = c.text("email"), password = c.text("password");
        String role = c.text("role"), phone = c.text("phone");
        if (name == null) throw new ApiException(400, "Enter your name");
        if (email == null || !EMAIL.matcher(email).matches()) throw new ApiException(400, "Enter a valid email address");
        if (password == null || password.length() < 6) throw new ApiException(400, "Choose a password with at least 6 characters");
        if (!List.of("customer", "owner", "driver").contains(role)) throw new ApiException(400, "Choose how you will use EverHungry");
        String restaurantName = c.text("restaurantName");
        if (role.equals("owner") && restaurantName == null) throw new ApiException(400, "Enter your restaurant's name");
        email = email.toLowerCase();
        if (Db.one("SELECT id FROM users WHERE email = ?", email) != null) throw new ApiException(409, "That email already has an account. Log in instead.");

        long id;
        try (Connection cn = Db.get()) {
            cn.setAutoCommit(false);
            try {
                id = Db.insert(cn, "INSERT INTO users(name,email,password_hash,role,phone) VALUES(?,?,?,?,?)",
                        name, email, Auth.hash(password), role, phone);
                if (role.equals("owner")) {
                    Db.insert(cn, "INSERT INTO restaurants(owner_id,name,cuisine,description,address,lat,lng,is_open) VALUES(?,?,?,?,?,?,?,FALSE)",
                            id, restaurantName, c.text("cuisine"), "", "Add your address in Restaurant settings", DEFAULT_LAT, DEFAULT_LNG);
                }
                cn.commit();
            } catch (Exception e) {
                cn.rollback();
                throw e;
            }
        }
        Auth.startSession(c.ex, id);
        return Map.of("user", Db.one("SELECT id, name, email, role, phone FROM users WHERE id = ?", id));
    }

    private Object login(Ctx c) {
        String email = c.text("email"), password = c.text("password");
        Map<String, Object> u = email == null ? null : Db.one("SELECT * FROM users WHERE email = ?", email.toLowerCase());
        if (u == null || password == null || !Auth.verify(password, (String) u.get("password_hash"))) {
            throw new ApiException(401, "Email or password is incorrect");
        }
        if (Db.bool(u.get("suspended"))) throw new ApiException(403, "This account is suspended. Contact the EverHungry admin.");
        Auth.startSession(c.ex, Db.num(u.get("id")));
        u.remove("password_hash");
        u.remove("suspended");
        u.remove("created_at");
        return Map.of("user", u);
    }

    // ------------------------------------------------------------ restaurants

    private Map<String, Object> ownRestaurant(Ctx c) {
        Map<String, Object> r = Db.one("SELECT id, name, cuisine, description, address, lat, lng, is_open, prep_minutes "
                + "FROM restaurants WHERE owner_id = ?", c.uid());
        if (r == null) throw new ApiException(404, "No restaurant is linked to this account");
        return r;
    }

    private Object updateRestaurant(Ctx c) {
        Map<String, Object> r = ownRestaurant(c);
        String name = c.text("name");
        if (name == null) throw new ApiException(400, "Enter the restaurant's name");
        Double lat = c.dbl("lat"), lng = c.dbl("lng");
        if (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new ApiException(400, "Set the restaurant's location on the map");
        Double prep = c.dbl("prepMinutes");
        int prepMinutes = prep == null ? 20 : (int) Math.max(5, Math.min(90, prep));
        Db.update("UPDATE restaurants SET name=?, cuisine=?, description=?, address=?, lat=?, lng=?, is_open=?, prep_minutes=? WHERE id=?",
                name, c.text("cuisine"), c.text("description"), c.text("address"), lat, lng,
                Boolean.TRUE.equals(c.bool("isOpen")), prepMinutes, r.get("id"));
        return ownRestaurant(c);
    }

    private Object addMenuItem(Ctx c) {
        Map<String, Object> r = ownRestaurant(c);
        String name = c.text("name");
        Double price = c.dbl("price");
        if (name == null) throw new ApiException(400, "Enter the dish name");
        if (price == null || price <= 0 || price > 100000) throw new ApiException(400, "Enter a price above zero");
        long id = Db.insert("INSERT INTO menu_items(restaurant_id,name,description,price) VALUES(?,?,?,?)",
                r.get("id"), name, c.text("description"), price);
        return Db.one("SELECT id, name, description, price, available FROM menu_items WHERE id = ?", id);
    }

    private void ownMenuItem(Ctx c) {
        Map<String, Object> r = ownRestaurant(c);
        if (Db.one("SELECT id FROM menu_items WHERE id = ? AND restaurant_id = ?", c.id("id"), r.get("id")) == null) {
            throw new ApiException(404, "Dish not found");
        }
    }

    private Object updateMenuItem(Ctx c) {
        ownMenuItem(c);
        String name = c.text("name");
        Double price = c.dbl("price");
        if (name == null) throw new ApiException(400, "Enter the dish name");
        if (price == null || price <= 0 || price > 100000) throw new ApiException(400, "Enter a price above zero");
        Db.update("UPDATE menu_items SET name=?, description=?, price=?, available=? WHERE id=?",
                name, c.text("description"), price, !Boolean.FALSE.equals(c.bool("available")), c.id("id"));
        return Db.one("SELECT id, name, description, price, available FROM menu_items WHERE id = ?", c.id("id"));
    }

    // ----------------------------------------------------------------- orders

    private Object createOrder(Ctx c) throws Exception {
        long restaurantId = c.dbl("restaurantId") == null ? 0 : c.dbl("restaurantId").longValue();
        String address = c.text("address");
        Double lat = c.dbl("lat"), lng = c.dbl("lng");
        JsonNode items = c.body.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) throw new ApiException(400, "Your cart is empty");
        if (address == null) throw new ApiException(400, "Add a delivery address");
        if (lat == null || lng == null) throw new ApiException(400, "Pin your delivery spot on the map");
        Map<String, Object> r = Db.one("SELECT id, is_open FROM restaurants WHERE id = ?", restaurantId);
        if (r == null) throw new ApiException(404, "Restaurant not found");
        if (!Db.bool(r.get("is_open"))) throw new ApiException(409, "This restaurant is not taking orders right now");

        long orderId;
        try (Connection cn = Db.get()) {
            cn.setAutoCommit(false);
            try {
                double subtotal = 0;
                List<Object[]> lines = new ArrayList<>();
                for (JsonNode it : items) {
                    long itemId = it.path("itemId").asLong();
                    int qty = it.path("qty").asInt();
                    if (qty < 1 || qty > 20) throw new ApiException(400, "Quantities must be between 1 and 20");
                    Map<String, Object> m = Db.one(cn, "SELECT id, name, price FROM menu_items WHERE id = ? AND restaurant_id = ? AND available",
                            itemId, restaurantId);
                    if (m == null) throw new ApiException(409, "A dish in your cart is no longer available. Review your cart.");
                    double price = ((Number) m.get("price")).doubleValue();
                    subtotal += price * qty;
                    lines.add(new Object[]{itemId, m.get("name"), price, qty});
                }
                double fee = Config.deliveryFee();
                double total = Math.round((subtotal + fee) * 100) / 100.0;
                orderId = Db.insert(cn, "INSERT INTO orders(customer_id,restaurant_id,status,delivery_fee,total,address,dest_lat,dest_lng,note) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)", c.uid(), restaurantId, "PLACED", fee, total, address, lat, lng, c.text("note"));
                for (Object[] l : lines) {
                    Db.insert(cn, "INSERT INTO order_items(order_id,item_id,name,price,qty) VALUES(?,?,?,?,?)", orderId, l[0], l[1], l[2], l[3]);
                }
                Db.insert(cn, "INSERT INTO order_events(order_id,status) VALUES(?,?)", orderId, "PLACED");
                cn.commit();
            } catch (Exception e) {
                cn.rollback();
                throw e;
            }
        }
        Events.orderChanged(orderId);
        return orderView(orderId);
    }

    private Object listOrders(Ctx c) {
        String where = switch (c.role()) {
            case "customer" -> "o.customer_id = ?";
            case "owner" -> "r.owner_id = ?";
            default -> "o.driver_id = ?";
        };
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : Db.query("SELECT o.id FROM orders o JOIN restaurants r ON r.id = o.restaurant_id "
                + "WHERE " + where + " ORDER BY o.id DESC LIMIT 60", c.uid())) {
            out.add(orderView(Db.num(row.get("id"))));
        }
        return out;
    }

    static Map<String, Object> orderView(long id) {
        Map<String, Object> o = Db.one("SELECT o.id, o.customer_id, o.restaurant_id, o.driver_id, o.status, o.delivery_fee, o.total, "
                + "o.address, o.dest_lat, o.dest_lng, o.note, o.created_at, o.updated_at, r.owner_id, r.name AS restaurant_name, "
                + "r.address AS restaurant_address, r.lat AS restaurant_lat, r.lng AS restaurant_lng, r.prep_minutes, "
                + "cu.name AS customer_name, d.name AS driver_name "
                + "FROM orders o JOIN restaurants r ON r.id = o.restaurant_id JOIN users cu ON cu.id = o.customer_id "
                + "LEFT JOIN users d ON d.id = o.driver_id WHERE o.id = ?", id);
        if (o == null) return null;
        o.put("items", Db.query("SELECT item_id, name, price, qty FROM order_items WHERE order_id = ? ORDER BY id", id));
        o.put("events", Db.query("SELECT status, created_at FROM order_events WHERE order_id = ? ORDER BY id", id));
        if (o.get("driver_id") != null) {
            o.put("driver_location", Db.one("SELECT lat, lng, updated_at FROM driver_locations WHERE driver_id = ?", o.get("driver_id")));
        }
        return o;
    }

    private static boolean canView(Map<String, Object> o, Ctx c) {
        long uid = c.uid();
        return switch (c.role()) {
            case "customer" -> Db.num(o.get("customer_id")) == uid;
            case "owner" -> Db.num(o.get("owner_id")) == uid;
            case "admin" -> true;
            default -> o.get("driver_id") != null && Db.num(o.get("driver_id")) == uid;
        };
    }

    private Object changeStatus(Ctx c) {
        long id = c.id("id");
        Map<String, Object> o = orderView(id);
        if (o == null || !canView(o, c)) throw new ApiException(404, "Order not found");
        String from = (String) o.get("status"), to = c.text("status");
        if (to == null || !OrderFlow.allowed(from, to, c.role())) {
            throw new ApiException(409, "This order cannot move from " + from + " to " + to);
        }
        if (Db.update("UPDATE orders SET status = ? WHERE id = ? AND status = ?", to, id, from) == 0) {
            throw new ApiException(409, "This order was just updated. Refresh to see its latest status.");
        }
        Db.insert("INSERT INTO order_events(order_id,status) VALUES(?,?)", id, to);
        Events.orderChanged(id);
        return orderView(id);
    }

    private Object claimOrder(Ctx c) {
        long id = c.id("id");
        Map<String, Object> active = Db.one("SELECT COUNT(*) AS n FROM orders WHERE driver_id = ? "
                + "AND status IN ('ACCEPTED','PREPARING','READY','PICKED_UP','ON_THE_WAY')", c.uid());
        if (Db.num(active.get("n")) >= 3) throw new ApiException(409, "Finish a delivery before taking another (limit is 3 at a time)");
        int n = Db.update("UPDATE orders SET driver_id = ? WHERE id = ? AND driver_id IS NULL "
                + "AND status IN ('ACCEPTED','PREPARING','READY')", c.uid(), id);
        if (n == 0) throw new ApiException(409, "Another driver already took this order");
        Db.insert("INSERT INTO order_events(order_id,status) VALUES(?,?)", id, "DRIVER_ASSIGNED");
        Events.orderChanged(id);
        Events.toRole("driver", Map.of("type", "available"));
        return orderView(id);
    }

    // ------------------------------------------------------------------ admin

    private Object adminStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", Db.one("SELECT (SELECT COUNT(*) FROM orders) AS orders, "
                + "(SELECT COALESCE(SUM(total), 0) FROM orders WHERE status = 'DELIVERED') AS revenue, "
                + "(SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','REJECTED','CANCELLED')) AS active, "
                + "(SELECT COUNT(*) FROM restaurants) AS restaurants, "
                + "(SELECT COUNT(*) FROM users WHERE role = 'customer') AS customers, "
                + "(SELECT COUNT(*) FROM users WHERE role = 'driver') AS drivers"));
        out.put("daily", Db.query("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, COUNT(*) AS orders, "
                + "COALESCE(SUM(CASE WHEN status = 'DELIVERED' THEN total ELSE 0 END), 0) AS revenue "
                + "FROM orders WHERE created_at >= (CURRENT_DATE - INTERVAL 6 DAY) "
                + "GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d') ORDER BY day"));
        out.put("statuses", Db.query("SELECT status, COUNT(*) AS n FROM orders GROUP BY status"));
        return out;
    }

    private Object suspendUser(Ctx c) {
        Map<String, Object> u = Db.one("SELECT id, role FROM users WHERE id = ?", c.id("id"));
        if (u == null) throw new ApiException(404, "User not found");
        if ("admin".equals(u.get("role"))) throw new ApiException(409, "Admin accounts cannot be suspended");
        Db.update("UPDATE users SET suspended = ? WHERE id = ?", Boolean.TRUE.equals(c.bool("suspended")), c.id("id"));
        return Map.of("ok", true);
    }

    private Object saveLocation(Ctx c) {
        Double lat = c.dbl("lat"), lng = c.dbl("lng");
        if (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new ApiException(400, "Invalid location");
        Db.update("INSERT INTO driver_locations(driver_id, lat, lng) VALUES(?,?,?) "
                + "ON DUPLICATE KEY UPDATE lat = VALUES(lat), lng = VALUES(lng)", c.uid(), lat, lng);
        for (Map<String, Object> o : Db.query("SELECT id, customer_id FROM orders WHERE driver_id = ? "
                + "AND status IN ('PICKED_UP','ON_THE_WAY')", c.uid())) {
            Events.toUser(Db.num(o.get("customer_id")),
                    Map.of("type", "location", "orderId", o.get("id"), "lat", lat, "lng", lng));
        }
        return Map.of("ok", true);
    }
}
