package com.amazon.tests.config.sharding;



/** CI path — exactly what exists today. Assumes docker-compose already
 * started order-db-shard0..3 + toxiproxy; connects to them, changes nothing. */
public class ComposeShardTestEnvironment implements ShardTestEnvironment {

    private ShardTopologyConfig topology;
    private ToxiproxyShardController toxiproxy;

    @Override
    public void start() throws Exception {
        topology = ShardTopologyConfig.load("shard-topology.properties");
        toxiproxy = new ToxiproxyShardController("toxiproxy", 8474, topology);
    }

    @Override
    public void stop() {
        // No-op — Jenkins pipeline owns compose teardown (docker-compose down -v)
    }

    @Override
    public ShardTopologyConfig getTopology() { return topology; }

    @Override
    public ToxiproxyShardController getToxiproxyController() { return toxiproxy; }
}
