package com.amazon.tests.builder;

import com.amazon.tests.config.DatabaseConfig;
import com.amazon.tests.config.DatabaseConnectionManager;
import com.amazon.tests.config.DatabaseTransactionManager;
import com.amazon.tests.config.QueryExecutor;
import com.amazon.tests.config.ResultMapper;
import com.amazon.tests.config.RetryPolicy;
import com.amazon.tests.provider.DatabaseDataProvider;

public class DatabaseDataProviderBuilder {

    private DatabaseConfig databaseConfig;
    private DatabaseConnectionManager connectionManager;
    private DatabaseTransactionManager transactionManager;
    private RetryPolicy retryPolicy;
    private QueryExecutor queryExecutor;
    private ResultMapper resultMapper;

    private DatabaseDataProviderBuilder() {
    }

    public static DatabaseDataProviderBuilder builder() {
        return new DatabaseDataProviderBuilder();
    }

    /**
     * Mandatory configuration.
     */
    public DatabaseDataProviderBuilder withDatabaseConfig(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
        return this;
    }

    /**
     * Optional override.
     */
    public DatabaseDataProviderBuilder withConnectionManager(
            DatabaseConnectionManager connectionManager) {

        this.connectionManager = connectionManager;
        return this;
    }

    /**
     * Optional override.
     */
    public DatabaseDataProviderBuilder withTransactionManager(
            DatabaseTransactionManager transactionManager) {

        this.transactionManager = transactionManager;
        return this;
    }

    /**
     * Optional override.
     */
    public DatabaseDataProviderBuilder withRetryPolicy(
            RetryPolicy retryPolicy) {

        this.retryPolicy = retryPolicy;
        return this;
    }

    /**
     * Optional override.
     */
    public DatabaseDataProviderBuilder withQueryExecutor(
            QueryExecutor queryExecutor) {

        this.queryExecutor = queryExecutor;
        return this;
    }

    /**
     * Optional override.
     */
    public DatabaseDataProviderBuilder withResultMapper(
            ResultMapper resultMapper) {

        this.resultMapper = resultMapper;
        return this;
    }

    public DatabaseDataProvider build() {

        validate();

        // Build dependency graph

        if (connectionManager == null) {
            connectionManager =
                    new DatabaseConnectionManager(databaseConfig);
        }

        if (retryPolicy == null) {
            retryPolicy =
                    new RetryPolicy(3, 1000);
        }

        if (transactionManager == null) {
            transactionManager =
                    new DatabaseTransactionManager(connectionManager);
        }

        if (queryExecutor == null) {
            queryExecutor =
                    new QueryExecutor(connectionManager, retryPolicy);
        }

        if (resultMapper == null) {
            resultMapper =
                    new ResultMapper();
        }

        return new DatabaseDataProvider(
                queryExecutor,
                resultMapper);
    }

    private void validate() {

        if (databaseConfig == null) {
            throw new IllegalStateException(
                    "DatabaseConfig is mandatory.");
        }
    }
}