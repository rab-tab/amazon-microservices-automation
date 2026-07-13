package com.amazon.tests.config;

public class DatabaseConfig {

    private final String host;
    private final int port;
    private final String databaseName;
    private final String username;
    private final String password;
    private final int connectionTimeout;
    private final int maxPoolSize;

    public DatabaseConfig(String host,
                          int port,
                          String databaseName,
                          String username,
                          String password,
                          int connectionTimeout,
                          int maxPoolSize) {

        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.connectionTimeout = connectionTimeout;
        this.maxPoolSize = maxPoolSize;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * Reads configuration based on the current execution environment.
     */
    public static DatabaseConfig fromEnvironment() {

        // Later this can read:
        // application.properties
        // Jenkins env vars
        // AWS Secrets Manager
        // Vault

        return new DatabaseConfig(
                "localhost",
                5432,
                "amazon",
                "postgres",
                "postgres",
                30,
                20);
    }
}
