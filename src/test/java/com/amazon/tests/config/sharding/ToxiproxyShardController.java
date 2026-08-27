package com.amazon.tests.config.sharding;


import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ASSUMPTION: no existing Toxiproxy wrapper class was found in what's been
 * shared so far — this uses the eu.rekawek.toxiproxy:toxiproxy-java client
 * directly. If a wrapper already exists elsewhere in the framework
 * (chaos/toxiproxy package), this should be consolidated with it rather
 * than kept as a parallel implementation.
 */
public class ToxiproxyShardController {

    private final ToxiproxyClient client;
    private final Map<Integer, Proxy> shardProxies = new HashMap<>();

    public ToxiproxyShardController(String toxiproxyHost, int toxiproxyControlPort,
                                    ShardTopologyConfig topology) throws IOException {
        this.client = new ToxiproxyClient(toxiproxyHost, toxiproxyControlPort);

        for (ShardTopologyConfig.ShardEndpoint shard : topology.getShards()) {
            String proxyName = "shard" + shard.index;
            int listenPort = 15432 + shard.index;
            String upstream = "order-db-shard" + shard.index + ":5432";

            Proxy proxy = client.createProxy(proxyName, "0.0.0.0:" + listenPort, upstream);
            shardProxies.put(shard.index, proxy);
        }
    }

    public void takeDown(int shardIndex) throws IOException {
        getProxy(shardIndex).disable();
    }

    public void bringUp(int shardIndex) throws IOException {
        getProxy(shardIndex).enable();
    }

    public void injectLatency(int shardIndex, long latencyMs, long jitterMs) throws IOException {
        getProxy(shardIndex).toxics()
                .latency("latency-shard" + shardIndex, ToxicDirection.DOWNSTREAM, latencyMs)
                .setJitter(jitterMs);
    }

    public void removeLatency(int shardIndex) throws IOException {
        getProxy(shardIndex).toxics().get("latency-shard" + shardIndex).remove();
    }

    public void resetAll() throws IOException {
        for (Proxy proxy : shardProxies.values()) {
            proxy.enable();
            proxy.toxics().getAll().forEach(t -> {
                try { t.remove(); } catch (IOException ignored) {}
            });
        }
    }

    private Proxy getProxy(int shardIndex) {
        Proxy proxy = shardProxies.get(shardIndex);
        if (proxy == null) {
            throw new IllegalArgumentException("No Toxiproxy proxy configured for shard " + shardIndex);
        }
        return proxy;
    }
}