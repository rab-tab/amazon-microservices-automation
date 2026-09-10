# Toxiproxy — Local Redis Chaos Testing Setup

This folder holds the config for a **persistent, local Toxiproxy instance**
that sits between order-service and Redis. It replaces the old
Testcontainers-based setup in `OrderIdempotencyRedisFailuresTest`, which
required manually restarting order-service pointed at a fresh proxy before
every run.

With this setup, order-service is **always** wired through Toxiproxy — there
is no manual precondition, and `OrderIdempotencyRedisFailuresTest` just talks
to the already-running proxy via its admin API.

## Why this exists

- No Docker required — `toxiproxy-server` is a single native binary.
- No manual "restart order-service pointed at the proxy" step before chaos
  runs — order-service's local profile points at the proxy permanently, so
  there's nothing to misconfigure per run.
- With no toxics active, the proxy is fully transparent — day-to-day local
  dev (running order-service, hitting Redis normally) behaves exactly as if
  Toxiproxy weren't there at all.

## One-time setup

### 1. Install toxiproxy-server

No Homebrew/Xcode required — download the prebuilt binary directly:

```bash
curl -L -o toxiproxy-server \
  https://github.com/Shopify/toxiproxy/releases/download/v2.5.0/toxiproxy-server-darwin-amd64
chmod +x toxiproxy-server
sudo mv toxiproxy-server /usr/local/bin/toxiproxy-server
```

(Use `toxiproxy-server-darwin-arm64` instead if `uname -m` reports `arm64`.)

If macOS Gatekeeper blocks the first run: **System Preferences → Security &
Privacy → General** → "Allow Anyway" next to toxiproxy-server, then re-run.

Verify:

```bash
toxiproxy-server -version   # should print 2.5.0
```

### 2. Start toxiproxy-server with this folder's config

From this directory:

```bash
toxiproxy-server -config toxiproxy.json &
```

This starts the proxy admin API on `127.0.0.1:8474` (Toxiproxy's default) and
creates the `redis` proxy defined in `toxiproxy.json`, listening on
`127.0.0.1:8666` and forwarding to `127.0.0.1:6379` (local Redis).

Leave this running in the background for as long as you're doing local dev
or chaos testing — it doesn't need to be restarted between test runs.

### 3. Point order-service at the proxy, not Redis directly

In order-service's local profile (`application-local.yml`, or whichever
profile you run locally), change:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

to:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 8666   # <- Toxiproxy's "redis" proxy, not Redis directly
```

Restart order-service once after this change. From then on, every Redis call
it makes flows through Toxiproxy — no further reconfiguration needed, ever.

## Running the chaos tests

With `toxiproxy-server` running and order-service pointed at port 8666,
`OrderIdempotencyRedisFailuresTest` connects to the proxy's admin API
directly (no container startup) and injects/removes toxics per test. Just
run the suite normally.

## Day-to-day local dev (no chaos)

Nothing changes. With no toxics active, the proxy passes traffic through
untouched — order-service behaves identically to talking to Redis directly.
You can leave `toxiproxy-server` running all the time and forget about it
until you actually want to run the chaos suite.

## Files

- `toxiproxy.json` — proxy definitions loaded on `toxiproxy-server` startup.
  Currently defines one proxy: `redis` (127.0.0.1:8666 → 127.0.0.1:6379).

## Troubleshooting

- **Chaos tests seem to have no effect** — almost always means order-service
  is still pointed at port 6379 (Redis directly) instead of 8666 (the
  proxy). Double-check step 3 above and confirm order-service was restarted
  after the config change.
- **`toxiproxy-server` won't start / port already in use** — another
  instance may already be running in the background from an earlier
  session. Check with `ps aux | grep toxiproxy-server` before starting a
  second one.
- **Toxics left over from a failed test run** — connect with
  `toxiproxy-cli` or hit the admin API directly to clear them:
  `curl -X POST http://127.0.0.1:8474/reset` resets all proxies to their
  default (toxic-free) state without restarting the process.