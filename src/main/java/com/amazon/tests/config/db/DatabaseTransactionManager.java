package com.amazon.tests.config.db;



import com.amazon.tests.config.db.DatabaseConnectionManager;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseTransactionManager {

    private final DatabaseConnectionManager connectionManager;

    public DatabaseTransactionManager(DatabaseConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Starts a new transaction.
     */
    public Connection beginTransaction() {

        try {

            Connection connection = connectionManager.getConnection();

            connection.setAutoCommit(false);

            return connection;

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to begin database transaction.",
                    e);
        }
    }

    /**
     * Commits the current transaction.
     */
    public void commit(Connection connection) {

        try {

            if (connection != null) {

                connection.commit();

                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to commit transaction.",
                    e);
        }
    }

    /**
     * Rolls back the current transaction.
     */
    public void rollback(Connection connection) {

        try {

            if (connection != null) {

                connection.rollback();

                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to rollback transaction.",
                    e);
        }
    }

    /**
     * Closes the connection.
     */
    public void close(Connection connection) {

        try {

            if (connection != null && !connection.isClosed()) {
                connection.close();
            }

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Unable to close database connection.",
                    e);
        }
    }
}
