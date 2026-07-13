package com.amazon.tests.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class QueryExecutor {

    private final DatabaseConnectionManager connectionManager;
    private final RetryPolicy retryPolicy;

    public QueryExecutor(DatabaseConnectionManager connectionManager,
                         RetryPolicy retryPolicy) {

        this.connectionManager = connectionManager;
        this.retryPolicy = retryPolicy;
    }

    public ResultSet execute(String sql) {

        int attempt = 1;

        while (true) {

            try {

                Connection connection =
                        connectionManager.getConnection();

                Statement statement =
                        connection.createStatement();

                return statement.executeQuery(sql);

            } catch (Exception ex) {

                if (!retryPolicy.shouldRetry(attempt, ex)) {

                    throw new RuntimeException(
                            "Query execution failed after "
                                    + attempt
                                    + " attempts.",
                            ex);

                }

                attempt++;

                sleep(retryPolicy.getRetryIntervalMillis());
            }
        }
    }

    private void sleep(long millis) {

        try {

            Thread.sleep(millis);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new RuntimeException(e);

        }

    }

}