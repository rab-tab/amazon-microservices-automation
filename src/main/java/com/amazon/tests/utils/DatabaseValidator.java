package com.amazon.tests.utils;

import com.amazon.tests.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Thread-safe database validator with connection pooling.
 * Uses HikariCP for efficient connection management.
 * Each service database has its own connection pool.
 */
@Slf4j
public class DatabaseValidator {

    // ✅ Singleton instance
    private static volatile DatabaseValidator instance;

    // ✅ Connection pools (one per database)
    private final DataSource usersDataSource;
    private final DataSource productsDataSource;
    private final DataSource ordersDataSource;
    private final DataSource paymentsDataSource;

    // ✅ Private constructor with connection pool initialization
    private DatabaseValidator() {
        log.info("Initializing DatabaseValidator with connection pools...");

        String dbHost = ConfigManager.getInstance().getConfig().databaseHost();
        String dbPort = ConfigManager.getInstance().getConfig().databasePort();
        String dbUser = ConfigManager.getInstance().getConfig().databaseUsername();
        String dbPass = ConfigManager.getInstance().getConfig().databasePassword();

        this.usersDataSource = createDataSource(dbHost, dbPort, "users_db", dbUser, dbPass);
        this.productsDataSource = createDataSource(dbHost, dbPort, "products_db", dbUser, dbPass);
        this.ordersDataSource = createDataSource(dbHost, dbPort, "orders_db", dbUser, dbPass);
        this.paymentsDataSource = createDataSource(dbHost, dbPort, "payments_db", dbUser, dbPass);

        log.info("DatabaseValidator initialized successfully");
    }

    // ✅ Singleton getInstance
    public static DatabaseValidator getInstance() {
        if (instance == null) {
            synchronized (DatabaseValidator.class) {
                if (instance == null) {
                    instance = new DatabaseValidator();
                }
            }
        }
        return instance;
    }

    /**
     * Create HikariCP DataSource for a database
     */
    private DataSource createDataSource(String host, String port, String dbName,
                                        String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s", host, port, dbName));
        config.setUsername(username);
        config.setPassword(password);

        // Connection pool settings
        config.setMaximumPoolSize(10);              // Max 10 connections per pool
        config.setMinimumIdle(2);                   // Keep 2 idle connections ready
        config.setConnectionTimeout(30000);         // 30 seconds
        config.setIdleTimeout(600000);              // 10 minutes
        config.setMaxLifetime(1800000);             // 30 minutes
        config.setAutoCommit(true);
        config.setConnectionTestQuery("SELECT 1");  // Health check query

        // Pool name for logging
        config.setPoolName("HikariPool-" + dbName);

        return new HikariDataSource(config);
    }

    // ===== USER DB ASSERTIONS =====

    public boolean userExistsByEmail(String email) {
        return queryExists(usersDataSource,
                "SELECT 1 FROM users WHERE email = ?", email);
    }

    public boolean userExistsById(String userId) {
        return queryExists(usersDataSource,
                "SELECT 1 FROM users WHERE id = ?::uuid", userId);
    }

    public Map<String, Object> getUserByEmail(String email) {
        return querySingleRow(usersDataSource,
                "SELECT * FROM users WHERE email = ?", email);
    }

    public String getUserStatusById(String userId) {
        return (String) queryScalar(usersDataSource,
                "SELECT status FROM users WHERE id = ?::uuid", userId);
    }

    public long countUsers() {
        Object result = queryScalar(usersDataSource, "SELECT COUNT(*) FROM users");
        return result != null ? ((Number) result).longValue() : 0L;
    }

    // ===== PRODUCT DB ASSERTIONS =====

    public boolean productExistsById(String productId) {
        return queryExists(productsDataSource,
                "SELECT 1 FROM products WHERE id = ?::uuid", productId);
    }

    public Map<String, Object> getProductById(String productId) {
        return querySingleRow(productsDataSource,
                "SELECT * FROM products WHERE id = ?::uuid", productId);
    }

    public int getProductStockById(String productId) {
        Object val = queryScalar(productsDataSource,
                "SELECT stock_quantity FROM products WHERE id = ?::uuid", productId);
        return val != null ? ((Number) val).intValue() : -1;
    }

    public String getProductStatusById(String productId) {
        return (String) queryScalar(productsDataSource,
                "SELECT status FROM products WHERE id = ?::uuid", productId);
    }

    // ===== ORDER DB ASSERTIONS =====

    public boolean orderExistsById(String orderId) {
        return queryExists(ordersDataSource,
                "SELECT 1 FROM orders WHERE id = ?::uuid", orderId);
    }

    public Map<String, Object> getOrderById(String orderId) {
        return querySingleRow(ordersDataSource,
                "SELECT * FROM orders WHERE id = ?::uuid", orderId);
    }

    public String getOrderStatus(String orderId) {
        return (String) queryScalar(ordersDataSource,
                "SELECT status FROM orders WHERE id = ?::uuid", orderId);
    }

    public long countOrdersByUserId(String userId) {
        Object result = queryScalar(ordersDataSource,
                "SELECT COUNT(*) FROM orders WHERE user_id = ?::uuid", userId);
        return result != null ? ((Number) result).longValue() : 0L;
    }

    public List<Map<String, Object>> getOrderItemsByOrderId(String orderId) {
        return queryMultipleRows(ordersDataSource,
                "SELECT * FROM order_items WHERE order_id = ?::uuid", orderId);
    }

    // ===== PAYMENT DB ASSERTIONS =====

    public boolean paymentExistsForOrder(String orderId) {
        return queryExists(paymentsDataSource,
                "SELECT 1 FROM payments WHERE order_id = ?::uuid", orderId);
    }

    public Map<String, Object> getPaymentByOrderId(String orderId) {
        return querySingleRow(paymentsDataSource,
                "SELECT * FROM payments WHERE order_id = ?::uuid", orderId);
    }

    public String getPaymentStatus(String orderId) {
        return (String) queryScalar(paymentsDataSource,
                "SELECT status FROM payments WHERE order_id = ?::uuid", orderId);
    }

    // ===== GENERIC QUERY HELPERS =====

    private boolean queryExists(DataSource dataSource, String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("DB query failed: {}", e.getMessage(), e);
            throw new DatabaseValidationException("Query exists failed: " + sql, e);
        }
    }

    private Object queryScalar(DataSource dataSource, String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getObject(1);
            }
        } catch (SQLException e) {
            log.error("DB scalar query failed: {}", e.getMessage(), e);
            throw new DatabaseValidationException("Scalar query failed: " + sql, e);
        }
        return null;
    }

    private Map<String, Object> querySingleRow(DataSource dataSource,
                                               String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return resultSetToMap(rs);
                }
            }
        } catch (SQLException e) {
            log.error("DB row query failed: {}", e.getMessage(), e);
            throw new DatabaseValidationException("Single row query failed: " + sql, e);
        }
        return Collections.emptyMap();
    }

    private List<Map<String, Object>> queryMultipleRows(DataSource dataSource,
                                                        String sql, Object... params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(resultSetToMap(rs));
            }
        } catch (SQLException e) {
            log.error("DB multi-row query failed: {}", e.getMessage(), e);
            throw new DatabaseValidationException("Multi-row query failed: " + sql, e);
        }
        return rows;
    }

    private void setParams(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private Map<String, Object> resultSetToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnName(i), rs.getObject(i));
        }
        return row;
    }

    /**
     * Shutdown all connection pools (call in @AfterSuite)
     */
    public void shutdown() {
        log.info("Shutting down DatabaseValidator connection pools...");
        closeDataSource(usersDataSource);
        closeDataSource(productsDataSource);
        closeDataSource(ordersDataSource);
        closeDataSource(paymentsDataSource);
        log.info("DatabaseValidator shutdown complete");
    }

    private void closeDataSource(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    /**
     * Custom exception for database validation errors
     */
    public static class DatabaseValidationException extends RuntimeException {
        public DatabaseValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}