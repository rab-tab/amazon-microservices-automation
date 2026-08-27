package com.amazon.tests.config.sharding;


public interface ShardTestEnvironment {

    ShardTopologyConfig getTopology();
    ToxiproxyShardController getToxiproxyController();

    /** Call once per suite, after environment is confirmed ready. */
    void start() throws Exception;

    /** Call once per suite, always — releases containers or leaves compose environment untouched. */
    void stop() throws Exception;

    static ShardTestEnvironment resolve() {
        String env = System.getProperty("test.env", System.getenv().getOrDefault("TEST_ENV", "ci"));
        return "local".equalsIgnoreCase(env)
                ? new TestcontainersShardTestEnvironment()
                : new ComposeShardTestEnvironment();
    }
}
