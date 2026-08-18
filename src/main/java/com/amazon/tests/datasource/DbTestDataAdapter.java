package com.amazon.tests.datasource;


import com.amazon.tests.config.db.DatabaseConnectionManager;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DbTestDataAdapter<T> implements TestDataSource<T> {
    private final DatabaseConnectionManager connectionManager;

    public DbTestDataAdapter(DatabaseConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public List<T> loadData(String sourceLocation, Class<T> type) {
        // sourceLocation is a raw SQL query here
        List<T> results = new ArrayList<>();
        Connection conn = connectionManager.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sourceLocation)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                results.add(mapRowToObject(rs, meta, columnCount, type));
            }
        } catch (SQLException e) {
            throw new TestDataLoadException("Failed to load DB test data for query: " + sourceLocation, e);
        } finally {
            connectionManager.close(conn);
        }
        return results;
    }

    private T mapRowToObject(ResultSet rs, ResultSetMetaData meta, int columnCount, Class<T> type) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                if (value == null) continue;

                Field field;
                try {
                    field = type.getDeclaredField(columnName);
                } catch (NoSuchFieldException e) {
                    continue;
                }
                field.setAccessible(true);
                setFieldValue(instance, field, value);
            }
            return instance;
        } catch (ReflectiveOperationException | SQLException e) {
            throw new TestDataLoadException("Failed to map DB row to " + type.getSimpleName(), e);
        }
    }

    private void setFieldValue(Object instance, Field field, Object value) throws IllegalAccessException {
        Class<?> fieldType = field.getType();
        if (fieldType.isInstance(value)) {
            field.set(instance, value);
        } else if (fieldType == String.class) {
            field.set(instance, value.toString());
        } else if (fieldType == int.class || fieldType == Integer.class) {
            field.set(instance, ((Number) value).intValue());
        } else if (fieldType == long.class || fieldType == Long.class) {
            field.set(instance, ((Number) value).longValue());
        } else if (fieldType == double.class || fieldType == Double.class) {
            field.set(instance, ((Number) value).doubleValue());
        } else if (fieldType == boolean.class || fieldType == Boolean.class) {
            field.set(instance, value);
        } else {
            throw new TestDataLoadException(
                    "Unsupported field type '" + fieldType.getSimpleName() + "' for field '" + field.getName() + "'");
        }
    }
}