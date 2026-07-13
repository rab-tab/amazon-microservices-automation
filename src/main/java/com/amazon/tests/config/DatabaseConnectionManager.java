package com.amazon.tests.config;



import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnectionManager {

    private final DatabaseConfig databaseConfig;

    public DatabaseConnectionManager(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    /**
     * Returns a new JDBC connection.
     */
    public Connection getConnection() {

        try {

            String url = String.format(
                    "jdbc:postgresql://%s:%d/%s",
                    databaseConfig.getHost(),
                    databaseConfig.getPort(),
                    databaseConfig.getDatabaseName());

            return DriverManager.getConnection(
                    url,
                    databaseConfig.getUsername(),
                    databaseConfig.getPassword());

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to establish database connection.",
                    e);
        }
    }

    /**
     * Closes the connection safely.
     */
    public void close(Connection connection) {

        if (connection == null) {
            return;
        }

        try {

            if (!connection.isClosed()) {
                connection.close();
            }

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to close database connection.",
                    e);
        }
    }
}
