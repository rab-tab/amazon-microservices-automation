package com.amazon.tests.utils;

import com.amazon.tests.config.TestConfig;
import lombok.extern.slf4j.Slf4j;
import org.aeonbits.owner.ConfigFactory;

import java.sql.*;
import java.util.*;

/**
 * Direct JDBC helper for DB-layer validation tests.
 *
 * Connects to each service's PostgreSQL database and allows
 * tests to assert on persisted data independently of the API,
 * verifying that service logic correctly writes to the DB.
 */
@Slf4j
public class DatabaseValidator {

    private static final String DB_HOST = System.getProperty("db.host", "localhost");
    private static final String DB_PORT = System.getProperty("db.port", "5432");
    private static final String DB_USER = System.getProperty("db.username", "amazon");
    private static final String DB_PASS = System.getProperty("db.password", "amazon123");

    private static final String USERS_DB_URL   = buildUrl("users_db");
    private static final String PRODUCTS_DB_URL = buildUrl("products_db");
    private static final String ORDERS_DB_URL   = buildUrl("orders_db");
    private static final String PAYMENTS_DB_URL = buildUrl("payments_db");

    private static String buildUrl(String db) {
        return "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + db;
    }

    // ─── Connection Factories ─────────────────────────────────────
    private static Connection getUsersDbConnection() throws SQLException {
        return DriverManager.getConnection(USERS_DB_URL, DB_USER, DB_PASS);
    }

    private static Connection getProductsDbConnection() throws SQLException {
        return DriverManager.getConnection(PRODUCTS_DB_URL, DB_USER, DB_PASS);
    }

    private static Connection getOrdersDbConnection() throws SQLException {
        return DriverManager.getConnection(ORDERS_DB_URL, DB_USER, DB_PASS);
    }

    private static Connection getPaymentsDbConnection() throws SQLException {
        return DriverManager.getConnection(PAYMENTS_DB_URL, DB_USER, DB_PASS);
    }

    // ─── User DB Assertions ───────────────────────────────────────

    public static boolean userExistsByEmail(String email) {
        return queryExists(getUsersDbConnection, "SELECT 1 FROM users WHERE email = ?", email);
    }

    public static boolean userExistsById(String userId) {
        return queryExists(getUsersDbConnection, "SELECT 1 FROM users WHERE id = ?::uuid", userId);
    }

    public static Map<String, Object> getUserByEmail(String email) {
        return querySingleRow(getUsersDbConnection, "SELECT * FROM users WHERE email = ?", email);
    }

    public static String getUserStatusById(String userId) {
        return (String) queryScalar(getUsersDbConnection,
                "SELECT status FROM users WHERE id = ?::uuid", userId);
    }

    public static long countUsers() {
        return (Long) queryScalar(getUsersDbConnection, "SELECT COUNT(*) FROM users");
    }

    // ─── Product DB Assertions ────────────────────────────────────

    public static boolean productExistsById(String productId) {
        return queryExists(getProductsDbConnection,
                "SELECT 1 FROM products WHERE id = ?::uuid", productId);
    }

    public static Map<String, Object> getProductById(String productId) {
        return querySingleRow(getProductsDbConnection,
                "SELECT * FROM products WHERE id = ?::uuid", productId);
    }

    public static int getProductStockById(String productId) {
        Object val = queryScalar(getProductsDbConnection,
                "SELECT stock_quantity FROM products WHERE id = ?::uuid", productId);
        return val != null ? ((Number) val).intValue() : -1;
    }

    public static String getProductStatusById(String productId) {
        return (String) queryScalar(getProductsDbConnection,
                "SELECT status FROM products WHERE id = ?::uuid", productId);
    }

    // ─── Order DB Assertions ──────────────────────────────────────

    public static boolean orderExistsById(String orderId) {
        return queryExists(getOrdersDbConnection,
                "SELECT 1 FROM orders WHERE id = ?::uuid", orderId);
    }

    public static Map<String, Object> getOrderById(String orderId) {
        return querySingleRow(getOrdersDbConnection,
                "SELECT * FROM orders WHERE id = ?::uuid", orderId);
    }

    public static String getOrderStatus(String orderId) {
        return (String) queryScalar(getOrdersDbConnection,
                "SELECT status FROM orders WHERE id = ?::uuid", orderId);
    }

    public static long countOrdersByUserId(String userId) {
        Object result = queryScalar(getOrdersDbConnection,
                "SELECT COUNT(*) FROM orders WHERE user_id = ?::uuid", userId);
        return result != null ? ((Number) result).longValue() : 0L;
    }

    public static List<Map<String, Object>> getOrderItemsByOrderId(String orderId) {
        return queryMultipleRows(getOrdersDbConnection,
                "SELECT * FROM order_items WHERE order_id = ?::uuid", orderId);
    }

    // ─── Payment DB Assertions ────────────────────────────────────

    public static boolean paymentExistsForOrder(String orderId) {
        return queryExists(getPaymentsDbConnection,
                "SELECT 1 FROM payments WHERE order_id = ?::uuid", orderId);
    }

    public static Map<String, Object> getPaymentByOrderId(String orderId) {
        return querySingleRow(getPaymentsDbConnection,
                "SELECT * FROM payments WHERE order_id = ?::uuid", orderId);
    }

    public static String getPaymentStatus(String orderId) {
        return (String) queryScalar(getPaymentsDbConnection,
                "SELECT status FROM payments WHERE order_id = ?::uuid", orderId);
    }

    // ─── Generic Query Helpers ────────────────────────────────────

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private static final ConnectionSupplier getUsersDbConnection   = DatabaseValidator::getUsersDbConnection;
    private static final ConnectionSupplier getProductsDbConnection = DatabaseValidator::getProductsDbConnection;
    private static final ConnectionSupplier getOrdersDbConnection   = DatabaseValidator::getOrdersDbConnection;
    private static final ConnectionSupplier getPaymentsDbConnection = DatabaseValidator::getPaymentsDbConnection;

    private static boolean queryExists(ConnectionSupplier connSupplier, String sql, Object... params) {
        try (Connection conn = connSupplier.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("DB query failed: {}", e.getMessage());
            return false;
        }
    }

    private static Object queryScalar(ConnectionSupplier connSupplier, String sql, Object... params) {
        try (Connection conn = connSupplier.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getObject(1);
            }
        } catch (SQLException e) {
            log.error("DB scalar query failed: {}", e.getMessage());
        }
        return null;
    }

    private static Map<String, Object> querySingleRow(ConnectionSupplier connSupplier,
                                                       String sql, Object... params) {
        try (Connection conn = connSupplier.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return resultSetToMap(rs);
                }
            }
        } catch (SQLException e) {
            log.error("DB row query failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private static List<Map<String, Object>> queryMultipleRows(ConnectionSupplier connSupplier,
                                                                String sql, Object... params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connSupplier.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(resultSetToMap(rs));
            }
        } catch (SQLException e) {
            log.error("DB multi-row query failed: {}", e.getMessage());
        }
        return rows;
    }

    private static void setParams(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static Map<String, Object> resultSetToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnName(i), rs.getObject(i));
        }
        return row;
    }
}
