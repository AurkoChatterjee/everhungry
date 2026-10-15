package com.everhungry;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Thin JDBC helper. Rows come back as maps keyed by column label. */
public final class Db {
    private Db() {}

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(Config.dbUrl(), Config.dbUser(), Config.dbPassword());
    }

    // ---- convenience versions that open their own connection ----

    public static List<Map<String, Object>> query(String sql, Object... p) {
        try (Connection c = get()) {
            return query(c, sql, p);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> one(String sql, Object... p) {
        List<Map<String, Object>> rows = query(sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(String sql, Object... p) {
        try (Connection c = get()) {
            return update(c, sql, p);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- versions that join an existing connection / transaction ----

    public static List<Map<String, Object>> query(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), norm(rs.getObject(i)));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    public static Map<String, Object> one(Connection c, String sql, Object... p) throws SQLException {
        List<Map<String, Object>> rows = query(c, sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            return ps.executeUpdate();
        }
    }

    public static long insert(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    public static long insert(String sql, Object... p) {
        try (Connection c = get()) {
            return insert(c, sql, p);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            if (p[i] == null) ps.setNull(i + 1, Types.NULL);
            else ps.setObject(i + 1, p[i]);
        }
    }

    private static Object norm(Object v) {
        if (v instanceof BigDecimal b) return b.doubleValue();
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof java.time.LocalDateTime l) return l.toString() + "Z";
        return v;
    }

    static long num(Object o) {
        return ((Number) o).longValue();
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o instanceof Number n && n.intValue() != 0;
    }
}
