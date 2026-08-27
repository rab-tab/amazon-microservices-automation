package com.amazon.tests.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class ShardAwareOrderDao {

    private final Map<Integer, HikariDataSource> shardDataSources = new HashMap<>();

    public ShardAwareOrderDao(ShardTopologyConfig topology) {
        for (ShardTopologyConfig.ShardEndpoint shard : topology.getShards()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(shard.url);
            cfg.setUsername(shard.username);
            cfg.setPassword(shard.password);
            cfg.setMaximumPoolSize(3);
            cfg.setPoolName("test-shard-" + shard.index + "-pool");
            shardDataSources.put(shard.index, new HikariDataSource(cfg));
        }
    }

    /**
     * ASSUMPTION: orders.id is a native Postgres UUID column, based on
     * PaymentResponse.id being typed UUID elsewhere in TestModels.
     * If orders.id is actually VARCHAR, replace setObject(1, UUID...)
     * with ps.setString(1, orderId) below — confirm against the real
     * order-service entity/schema before relying on this.
     */
    public boolean existsOnShard(int shardIndex, String orderId) {
        String sql = "SELECT 1 FROM orders WHERE id = ?";
        try (Connection conn = getDataSource(shardIndex).getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.fromString(orderId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed querying shard " + shardIndex, e);
        }
    }

    public List<Integer> findAllShardsContaining(String orderId) {
        List<Integer> found = new ArrayList<>();
        for (int shardIndex : shardDataSources.keySet()) {
            if (existsOnShard(shardIndex, orderId)) {
                found.add(shardIndex);
            }
        }
        return found;
    }

    public HikariDataSource getDataSource(int shardIndex) {
        HikariDataSource ds = shardDataSources.get(shardIndex);
        if (ds == null) {
            throw new IllegalArgumentException("No datasource configured for shard " + shardIndex);
        }
        return ds;
    }

    public void closeAll() {
        shardDataSources.values().forEach(HikariDataSource::close);
    }
}