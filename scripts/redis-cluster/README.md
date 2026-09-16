# Local Redis Cluster (3 masters + 3 replicas)

Stage 2 of the cluster-testing plan: a real, multi-node Redis Cluster running
locally, for testing order-service against actual cluster behavior
(failover, lock-duplication windows) rather than a single instance.

## Why grokzen/redis-cluster, not N separate containers

A "normal" multi-container Redis Cluster setup (one container per node) hits
a well-known problem when the client lives outside Docker: cluster nodes
gossip with each other using whatever address they *announce*, and that
address also gets handed back to clients in `MOVED`/`ASK` redirects. If
nodes announce a Docker-internal address, an external client (order-service
running locally) can't follow the redirects. If nodes announce `127.0.0.1`
instead, they can't gossip with *each other* (each container's `127.0.0.1`
means itself, not its neighbors). Solving this properly needs host
networking, which isn't reliably available the same way across
Linux/macOS/Windows.

`grokzen/redis-cluster` sidesteps this entirely: all 6 Redis instances run
inside **one** container, sharing its loopback interface. They gossip with
each other over `127.0.0.1` internally (works, since they're all the same
container), and that's the exact same `127.0.0.1` the host reaches them at
once ports are mapped out. No split-identity problem.

## One-time setup

### 1. Start the cluster

From this directory:

```bash
docker-compose up -d
```

First run pulls the image and takes a little while to form the cluster.
Watch the logs:

```bash
docker-compose logs -f
```

### 2. Verify the cluster is healthy

```bash
redis-cli -p 7000 cluster info
```

Look for `cluster_state:ok` and `cluster_known_nodes:6`. To see the
master/replica layout:

```bash
redis-cli -p 7000 cluster nodes
```

You should see 3 lines with `master` and 3 with `slave`, each slave
pointing at one master's node ID.

### 3. Point order-service at the cluster

This is the important part or the tests below will silently test nothing —
same failure mode as the single-instance Toxiproxy setup if the app isn't
actually pointed at the right thing.

Standalone Redis config (`spring.data.redis.host`/`port`) does **not** work
for a cluster — Spring Data Redis needs a different config shape entirely:

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - 127.0.0.1:7000
          - 127.0.0.1:7001
          - 127.0.0.1:7002
          - 127.0.0.1:7003
          - 127.0.0.1:7004
          - 127.0.0.1:7005
        max-redirects: 3
      timeout: 2000ms
```

This builds a `RedisClusterConfiguration` under the hood instead of a
standalone one — remove or comment out the old `host`/`port` keys, they're
ignored once `cluster.nodes` is set but leaving them in is confusing to
read later.

**This is a real code-path change, not just config** — confirm
`RedisConfig`'s `redisTemplate()` bean still builds correctly against a
`RedisConnectionFactory` backed by cluster nodes rather than a standalone
one. Worth a quick manual smoke test (create an order, confirm the
idempotency key shows up via `redis-cli -c -p 7000 keys 'idempotency:*'`)
before trusting any cluster-specific test results.

## Poking at individual nodes

`redis-cli -p 7000` connects to whichever node is on 7000 specifically —
add `-c` (cluster mode) so it automatically follows `MOVED` redirects to
whichever node actually owns a given key's hash slot:

```bash
redis-cli -c -p 7000
```

To find which node owns a specific key's slot:

```bash
redis-cli -p 7000 cluster keyslot idempotency:order:someuser:somekey
```

## Forcing a failover (for the actual chaos tests)

```bash
# Find a master's node ID and one of its replicas from `cluster nodes` output,
# then run this against the REPLICA (not the master) to promote it:
redis-cli -p <replica-port> cluster failover
```

This is a *graceful* failover — the master cooperates. To simulate a
genuinely crashed node instead (harder, more realistic, and the scenario
that actually risks the lock-duplication window from earlier):

```bash
docker exec redis-cluster-local redis-cli -p <master-port> debug sleep 30 &
# or, more brutally:
docker exec redis-cluster-local pkill -f "redis-server \*:<master-port>"
```

Killing the process outright is closer to a real crash and more likely to
expose replication-lag edge cases than a graceful `cluster failover`.

## Tearing down

```bash
docker-compose down
```

Add `-v` to also wipe the cluster's data volume if you want a completely
clean slate next time (otherwise node state/config persists across restarts,
including the cluster topology it already formed).

## Resource footprint

One container, ~6 lightweight Redis processes inside it — far lighter than
6 separate containers would be. Should be fine even on a space-constrained
machine; if it's still too much, `SLAVES_PER_MASTER: "0"` drops to 3
masters with no replicas (loses failover-testing ability entirely, so only
worth it if you just need to prove basic cluster-mode connectivity first).