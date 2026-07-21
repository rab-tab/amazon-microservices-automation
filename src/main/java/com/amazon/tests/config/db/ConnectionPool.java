package com.amazon.tests.config.db;


import com.amazon.tests.config.db.DatabaseConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConnectionPool {

    private final DatabaseConfig config;

    public ConnectionPool(DatabaseConfig config) {
        this.config = config;
    }

    public Connection getConnection() {

        try {

            String url = String.format(
                    "jdbc:postgresql://%s:%d/%s",
                    config.getHost(),
                    config.getPort(),
                    config.getDatabaseName());

            return DriverManager.getConnection(
                    url,
                    config.getUsername(),
                    config.getPassword());

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to create database connection.",
                    e);
        }
    }
}