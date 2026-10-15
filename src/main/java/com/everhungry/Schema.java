package com.everhungry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/** Creates the tables on first run and loads demo data into an empty database. */
public final class Schema {
    private static final Logger log = LoggerFactory.getLogger(Schema.class);
    private static final String TAIL = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private Schema() {}

    static void init() {
        try (Connection c = Db.get()) {
            Db.update(c, "CREATE TABLE IF NOT EXISTS users ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                    + "email VARCHAR(150) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, "
                    + "role VARCHAR(20) NOT NULL, phone VARCHAR(30), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)" + TAIL);
            Db.update(c, "CREATE TABLE IF NOT EXISTS restaurants ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, name VARCHAR(120) NOT NULL, "
                    + "cuisine VARCHAR(80), description VARCHAR(500), address VARCHAR(200), "
                    + "lat DOUBLE NOT NULL, lng DOUBLE NOT NULL, is_open BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "prep_minutes INT NOT NULL DEFAULT 20, FOREIGN KEY (owner_id) REFERENCES users(id))" + TAIL);
            Db.update(c, "CREATE TABLE IF NOT EXISTS menu_items ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurant_id BIGINT NOT NULL, name VARCHAR(120) NOT NULL, "
                    + "description VARCHAR(300), price DECIMAL(10,2) NOT NULL, available BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE)" + TAIL);
            Db.update(c, "CREATE TABLE IF NOT EXISTS orders ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, restaurant_id BIGINT NOT NULL, "
                    + "driver_id BIGINT NULL, status VARCHAR(20) NOT NULL, delivery_fee DECIMAL(10,2) NOT NULL, "
                    + "total DECIMAL(10,2) NOT NULL, address VARCHAR(255) NOT NULL, dest_lat DOUBLE NOT NULL, "
                    + "dest_lng DOUBLE NOT NULL, note VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (customer_id) REFERENCES users(id), FOREIGN KEY (restaurant_id) REFERENCES restaurants(id), "
                    + "FOREIGN KEY (driver_id) REFERENCES users(id), INDEX idx_orders_status (status))" + TAIL);
            Db.update(c, "CREATE TABLE IF NOT EXISTS order_items ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, item_id BIGINT NOT NULL, "
                    + "name VARCHAR(120) NOT NULL, price DECIMAL(10,2) NOT NULL, qty INT NOT NULL, "
                    + "FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE)" + TAIL);
            Db.update(c, "CREATE TABLE IF NOT EXISTS order_events ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE)" + TAIL);
            Db.update(c, "CREATE TABLE IF NOT EXISTS driver_locations ("
                    + "driver_id BIGINT PRIMARY KEY, lat DOUBLE NOT NULL, lng DOUBLE NOT NULL, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (driver_id) REFERENCES users(id))" + TAIL);

            addSuspendedColumn(c);
            if (Db.num(Db.one(c, "SELECT COUNT(*) AS n FROM users").get("n")) == 0) seed(c);
            ensureAdmin(c);
        } catch (SQLException e) {
            log.error("Could not reach MySQL at {} as '{}': {}", Config.dbUrl(), Config.dbUser(), e.getMessage());
            log.error("Start MySQL and check db.user / db.password in src/main/resources/application.properties "
                    + "(or set DB_USER and DB_PASSWORD environment variables).");
            System.exit(1);
        }
    }

    /** Older databases get the column added in place; error 1060 means it already exists. */
    private static void addSuspendedColumn(Connection c) throws SQLException {
        try {
            Db.update(c, "ALTER TABLE users ADD COLUMN suspended BOOLEAN NOT NULL DEFAULT FALSE");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1060) throw e;
        }
    }

    /** Makes sure at least one admin exists, without touching existing data. */
    private static void ensureAdmin(Connection c) throws SQLException {
        if (Db.one(c, "SELECT id FROM users WHERE role = 'admin' LIMIT 1") != null) return;
        user(c, "EverHungry Admin", "admin@everhungry.demo", Auth.hash("demo123"), "admin", null);
        log.info("Created admin account admin@everhungry.demo (password demo123 - change it before sharing the app)");
    }

    private static void seed(Connection c) throws SQLException {
        log.info("Empty database - loading demo data");
        String pw = Auth.hash("demo123");

        long customer = user(c, "Priya Sharma", "customer@everhungry.demo", pw, "customer", "9800000001");
        long owner1 = user(c, "Anil Verma", "owner1@everhungry.demo", pw, "owner", "9800000002");
        long owner2 = user(c, "Farah Khan", "owner2@everhungry.demo", pw, "owner", "9800000003");
        long owner3 = user(c, "Meera Nair", "owner3@everhungry.demo", pw, "owner", "9800000004");
        user(c, "Imran Ali", "driver1@everhungry.demo", pw, "driver", "9800000005");
        user(c, "Neha Joshi", "driver2@everhungry.demo", pw, "driver", "9800000006");

        long r1 = restaurant(c, owner1, "Spice Route Kitchen", "North Indian",
                "Slow-cooked curries and fresh tandoor breads.", "Zone 1, MP Nagar", 23.2332, 77.4346, 25);
        item(c, r1, "Paneer Butter Masala", "Cottage cheese in a creamy tomato gravy", 260);
        item(c, r1, "Dal Makhani", "Black lentils simmered overnight", 220);
        item(c, r1, "Butter Naan", "Tandoor-baked, brushed with butter", 45);
        item(c, r1, "Jeera Rice", "Basmati rice tempered with cumin", 140);
        item(c, r1, "Gulab Jamun", "Two warm syrup-soaked dumplings", 90);

        long r2 = restaurant(c, owner2, "Bhopal Biryani House", "Biryani and kebabs",
                "Dum biryani sealed and cooked in small batches.", "Chowk Bazaar, Old City", 23.2650, 77.4000, 30);
        item(c, r2, "Chicken Dum Biryani", "Layered basmati, house masala, boiled egg", 280);
        item(c, r2, "Veg Biryani", "Seasonal vegetables and saffron rice", 200);
        item(c, r2, "Mutton Seekh Kebab", "Four skewers, mint chutney", 320);
        item(c, r2, "Raita", "Whisked curd with cucumber", 60);
        item(c, r2, "Phirni", "Rice pudding set in a clay bowl", 80);

        long r3 = restaurant(c, owner3, "Lake View Cafe", "Cafe and snacks",
                "Coffee, sandwiches and desserts near the Upper Lake.", "Shamla Hills", 23.2500, 77.3950, 15);
        item(c, r3, "Cold Coffee", "Blended with ice cream", 140);
        item(c, r3, "Veg Club Sandwich", "Triple-decker, grilled, with fries", 170);
        item(c, r3, "Pasta Alfredo", "Penne in a white garlic sauce", 230);
        item(c, r3, "Brownie with Ice Cream", "Warm brownie, vanilla scoop", 160);
        item(c, r3, "Masala Fries", "Crisp fries tossed in chaat masala", 120);
        log.info("Demo data ready (password for every demo account: demo123)");
    }

    private static long user(Connection c, String name, String email, String hash, String role, String phone) throws SQLException {
        return Db.insert(c, "INSERT INTO users(name,email,password_hash,role,phone) VALUES(?,?,?,?,?)", name, email, hash, role, phone);
    }

    private static long restaurant(Connection c, long owner, String name, String cuisine, String desc,
                                   String address, double lat, double lng, int prep) throws SQLException {
        return Db.insert(c, "INSERT INTO restaurants(owner_id,name,cuisine,description,address,lat,lng,prep_minutes) VALUES(?,?,?,?,?,?,?,?)",
                owner, name, cuisine, desc, address, lat, lng, prep);
    }

    private static void item(Connection c, long restaurant, String name, String desc, double price) throws SQLException {
        Db.insert(c, "INSERT INTO menu_items(restaurant_id,name,description,price) VALUES(?,?,?,?)", restaurant, name, desc, price);
    }
}
