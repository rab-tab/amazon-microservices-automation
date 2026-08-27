package com.amazon.tests.config.sharding;

import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;

import java.util.ArrayList;
import java.util.List;

/**
 * Local-only environment. Spins up 4 disposable Postgres shards + Toxiproxy
 * via Testcontainers, on FIXED host ports (not Testcontainers' usual
 * dynamic ports) so order-service — started separately via your IDE —
 * can point at stable, known localhost addresses without needing ports
 * injected into its run config each time.
 *
 * Trade-off: fixed ports mean this environment can't run twice
 * concurrently on one machine. Fine for solo local dev; not usable for
 * CI, which is why CI stays on ComposeShardTestEnvironment instead.
 *
 * order-service itself is NOT managed here — start it separately with
 * the "sharded-local" profile active (see application-sharded-local.yml)
 * AFTER this environment reports ready.
 */
public class TestcontainersShardTestEnvironment implements ShardTestEnvironment {

    private static final int SHARD_COUNT = 4;
    private static final int[] SHARD_DB_HOST_PORTS = {5441, 5442, 5443, 5444};
    private static final int TOXIPROXY_CONTROL_PORT = 8474;
    private static final int[] TOXIPROXY_PROXY_HOST_PORTS = {15432, 15433, 15434, 15435};

    private final Network network = Network.newNetwork();
    private final List<PostgreSQLContainer<?>> shardContainers = new ArrayList<>();
    private ToxiproxyContainer toxiproxyContainer;
    private final List<ContainerProxy> proxies = new ArrayList<>();

    private ShardTopologyConfig topology;
    private ToxiproxyShardController toxiproxy;

    @Override
    public void start() throws Exception {
        for (int i = 0; i < SHARD_COUNT; i++) {
            int finalI = i;
            PostgreSQLContainer<?> shard = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("orders")
                    .withUsername("amazon")
                    .withPassword("amazon123")
                    .withNetwork(network)
                    .withNetworkAliases("order-db-shard" + i)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.bindPort(SHARD_DB_HOST_PORTS[finalI]),
                                    new com.github.dockerjava.api.model.ExposedPort(5432))));
            shard.start();
            shardContainers.add(shard);
        }

        toxiproxyContainer = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
                .withNetwork(network)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(
                                new com.github.dockerjava.api.model.PortBinding(
                                        com.github.dockerjava.api.model.Ports.Binding.bindPort(TOXIPROXY_CONTROL_PORT),
                                        new com.github.dockerjava.api.model.ExposedPort(8474)),
                                new com.github.dockerjava.api.model.PortBinding(
                                        com.github.dockerjava.api.model.Ports.Binding.bindPort(TOXIPROXY_PROXY_HOST_PORTS[0]),
                                        new com.github.dockerjava.api.model.ExposedPort(15432)),
                                new com.github.dockerjava.api.model.PortBinding(
                                        com.github.dockerjava.api.model.Ports.Binding.bindPort(TOXIPROXY_PROXY_HOST_PORTS[1]),
                                        new com.github.dockerjava.api.model.ExposedPort(15433)),
                                new com.github.dockerjava.api.model.PortBinding(
                                        com.github.dockerjava.api.model.Ports.Binding.bindPort(TOXIPROXY_PROXY_HOST_PORTS[2]),
                                        new com.github.dockerjava.api.model.ExposedPort(15434)),
                                new com.github.dockerjava.api.model.PortBinding(
                                        com.github.dockerjava.api.model.Ports.Binding.bindPort(TOXIPROXY_PROXY_HOST_PORTS[3]),
                                        new com.github.dockerjava.api.model.ExposedPort(15435))
                        ));
        toxiproxyContainer.start();

        // Verification DAO connects to the fixed host ports directly —
        // consistent with the "always bypass the proxy for verification" rule.
        topology = buildLocalTopology();

        // ToxiproxyShardController talks to the control API on localhost,
        // creating proxies that route to each shard's IN-NETWORK alias
        // (order-db-shardN:5432), not the host-mapped port — Toxiproxy
        // itself lives inside the Docker network alongside the shards.
        toxiproxy = new ToxiproxyShardController("localhost", TOXIPROXY_CONTROL_PORT, topology);

        printLocalOrderServiceInstructions();
    }

    private ShardTopologyConfig buildLocalTopology() {
        // ASSUMPTION: ShardTopologyConfig currently only supports loading
        // from a properties file (ShardTopologyConfig.load(resourcePath)).
        // This needs a second constructor/factory method accepting shard
        // endpoints built programmatically, e.g.:
        //   ShardTopologyConfig.fromEndpoints(List<ShardEndpoint> endpoints)
        // Not yet added to that class — flagging rather than guessing its
        // internals a third time.
        List<ShardTopologyConfig.ShardEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < SHARD_COUNT; i++) {
            endpoints.add(new ShardTopologyConfig.ShardEndpoint(
                    i,
                    "jdbc:postgresql://localhost:" + SHARD_DB_HOST_PORTS[i] + "/orders",
                    "amazon",
                    "amazon123"
            ));
        }
        return ShardTopologyConfig.fromEndpoints(endpoints); // TODO: add this factory method
    }

    private void printLocalOrderServiceInstructions() {
        System.out.println("""
                ────────────────────────────────────────────────────────
                Local shard environment ready.
                Start order-service separately (IDE run config) with:
                  SPRING_PROFILES_ACTIVE=local-test,cb-test,sharded-local
                using application-sharded-local.yml (localhost:15432-15435
                via Toxiproxy). Toxiproxy control API: http://localhost:8474
                ────────────────────────────────────────────────────────
                """);
    }

    @Override
    public void stop() {
        if (toxiproxyContainer != null) toxiproxyContainer.stop();
        shardContainers.forEach(PostgreSQLContainer::stop);
        network.close();
    }

    @Override
    public ShardTopologyConfig getTopology() { return topology; }

    @Override
    public ToxiproxyShardController getToxiproxyController() { return toxiproxy; }
}