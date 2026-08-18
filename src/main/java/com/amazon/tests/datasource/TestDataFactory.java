package com.amazon.tests.datasource;

import com.amazon.tests.config.db.DatabaseConnectionManager;

import java.util.List;

public class TestDataFactory {
    public static <T> List<T> load(String sourceLocation, Class<T> type) {
        TestDataSource<T> source = TestDataSourceRegistry.resolveFromPath(sourceLocation);
        return source.loadData(sourceLocation, type);
    }
    public static <T> List<T> loadFromDb(DatabaseConnectionManager connectionManager, String query, Class<T> type) {
        TestDataSource<T> source = TestDataSourceRegistry.resolveDb(connectionManager);
        return source.loadData(query, type);
    }
}
