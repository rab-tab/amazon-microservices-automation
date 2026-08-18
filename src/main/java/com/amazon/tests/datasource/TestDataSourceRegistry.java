package com.amazon.tests.datasource;

import com.amazon.tests.config.db.DatabaseConnectionManager;

public class TestDataSourceRegistry {
    public static <T> TestDataSource<T> resolve(DataFormat format) {
        return switch (format) {
            case JSON  -> new JsonTestDataAdapter<>();
            case EXCEL -> new ExcelTestDataAdapter<>();
            case DB    -> throw new UnsupportedOperationException("DbTestDataAdapter not wired up yet");
        };
    }

    // Convenience overload — infer format from the file path itself,
    // so callers don't need to know/pass DataFormat for file-based sources
    public static <T> TestDataSource<T> resolveFromPath(String sourceLocation) {
        return resolve(DataFormat.fromExtension(sourceLocation));
    }
    public static <T> TestDataSource<T> resolveDb(DatabaseConnectionManager connectionManager) {
        return new DbTestDataAdapter<>(connectionManager);
    }
}
