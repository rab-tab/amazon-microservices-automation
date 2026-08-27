package com.amazon.tests.config;



import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class ShardTopologyConfig {

    public static class ShardEndpoint {
        public final int index;
        public final String url;
        public final String username;
        public final String password;

        public ShardEndpoint(int index, String url, String username, String password) {
            this.index = index;
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }

    private final List<ShardEndpoint> shards = new ArrayList<>();

    public static ShardTopologyConfig load(String resourcePath) {
        Properties props = new Properties();
        try (InputStream in = ShardTopologyConfig.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("shard-topology.properties not found on classpath: " + resourcePath);
            }
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load shard topology config", e);
        }

        ShardTopologyConfig config = new ShardTopologyConfig();
        int shardCount = Integer.parseInt(props.getProperty("shard.count"));

        for (int i = 0; i < shardCount; i++) {
            config.shards.add(new ShardEndpoint(
                    i,
                    resolveEnv(props.getProperty("shard." + i + ".url")),
                    resolveEnv(props.getProperty("shard." + i + ".username")),
                    resolveEnv(props.getProperty("shard." + i + ".password"))
            ));
        }
        return config;
    }

    // properties file uses ${SHARD0_USER}-style placeholders — resolve against env vars
    private static String resolveEnv(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String envVar = value.substring(2, value.length() - 1);
            String resolved = System.getenv(envVar);
            if (resolved == null) {
                throw new IllegalStateException("Missing required env var: " + envVar);
            }
            return resolved;
        }
        return value;
    }

    public List<ShardEndpoint> getShards() {
        return shards;
    }

    public int getShardCount() {
        return shards.size();
    }
}