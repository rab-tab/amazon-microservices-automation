package com.amazon.tests.provider;


import com.amazon.tests.config.QueryExecutor;
import com.amazon.tests.config.ResultMapper;

import java.sql.ResultSet;

public class DatabaseDataProvider implements TestDataProvider {

    private final QueryExecutor queryExecutor;
    private final ResultMapper resultMapper;

    public DatabaseDataProvider(QueryExecutor queryExecutor,
                                ResultMapper resultMapper) {

        this.queryExecutor = queryExecutor;
        this.resultMapper = resultMapper;
    }

    @Override
    public <T> T load(Class<T> clazz, String key) {

        String sql = buildQuery(key);

        ResultSet resultSet = queryExecutor.execute(sql);

        return resultMapper.map(resultSet, clazz);
    }

    /**
     * Builds SQL for retrieving test data.
     * Later this can be replaced with PreparedStatement.
     */
    private String buildQuery(String key) {

        return """
                SELECT *
                FROM test_data
                WHERE data_key = '%s'
                """.formatted(key);
    }
}
