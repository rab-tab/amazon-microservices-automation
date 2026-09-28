package com.amazon.tests.utils;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * Starts (or reuses) a natively-installed toxiproxy-server and hands out
 * proxy handles. Extracted from OrderIdempotencyRedisFailuresTest so that
 * BaseTest can start it BEFORE the pre-suite health checks run.
 *
 * WHY THE ORDERING MATTERS: TestNG runs a base class's @BeforeSuite before
 * any subclass's, so a chaos test class starting Toxiproxy in its own
 * @BeforeSuite always runs AFTER BaseTest's health checks. When
 * order-service is pointed at the proxy port (8666), its /actuator/health
 * probes Redis through that port; with no proxy listening yet, the probe
 * fails and the health check aborts the suite before the proxy is ever
 * started.
 *
 * OPT-IN: BaseTest is inherited by every test class, so this only runs
 * when the TEST JVM is started with -Dtoxiproxy.enabled=true (a VM option
 * on the test run configuration, not on order-service). Regular runs never
 * need the toxiproxy binary or its config file.
 *
 * Ownership rule (unchanged from the original chaos suite): if an admin API
 * is already reachable it is reused and left running; a process is only
 * stopped in stopIfOwned() when THIS run started it.
 *
 * Deliberately avoids TimeUnit (does not resolve in this project's
 * environment): plain millis + Thread.sleep(long) throughout.
 */
@Slf4j
public final class ToxiproxyManager {

    private static final String ADMIN_HOST = "127.0.0.1";
    private static final int ADMIN_PORT = 8474; // toxiproxy-server default

    // Override with -Dtoxiproxy.binary=/some/other/path if it's not on PATH
    // for however tests get launched (IDE run configs don't always inherit
    // a shell's PATH).
    private static final String BINARY = System.getProperty("toxiproxy.binary", "toxiproxy-server");

    // Resolved against the test JVM's working directory. In a multi-module
    // IntelliJ project that can be the module dir rather than the repo root,
    // so -Dtoxiproxy.config=/absolute/path/toxiproxy.json is supported.
    private static final Path CONFIG =
            Paths.get(System.getProperty("toxiproxy.config", "scripts/toxiproxy.json")).toAbsolutePath();

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    // toxiproxy-server's own output for the instance THIS run starts. Previously discarded,
    // which hid config-loading errors: the server can stay up with zero proxies loaded.
    // Truncated on every start, so it only ever holds the current run.
    private static final File SERVER_LOG = new File(System.getProperty("java.io.tmpdir"), "toxiproxy-server.log");

    // Only set if THIS run started toxiproxy-server itself — null means an
    // already-running instance was reused (or nothing was started at all).
    private static Process ownedProcess;
    private static ToxiproxyClient client;

    private ToxiproxyManager() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("toxiproxy.enabled", "false"));
    }

    /**
     * Ensures a toxiproxy-server is reachable (reusing one or starting one)
     * and that each named proxy is defined in it. Idempotent.
     */
    public static synchronized void startIfNeeded(String... requiredProxies) {
        if (client == null) {
            if (isAdminApiReachable()) {
                log.info("🔌 Toxiproxy admin API already reachable at {}:{} — reusing existing instance",
                        ADMIN_HOST, ADMIN_PORT);
            } else {
                startOwnedProcess();
            }
            client = new ToxiproxyClient(ADMIN_HOST, ADMIN_PORT);
        }

        // Fail here, with a clear message, if a reused instance was started
        // without the config (so port 8666 would silently not be listening).
        for (String name : requiredProxies) {
            requireProxy(name);
        }
    }

    /** Returns a handle to a proxy defined in toxiproxy.json; fails loudly if unavailable. */
    public static synchronized Proxy requireProxy(String name) {
        if (client == null) {
            throw new IllegalStateException(
                    "Toxiproxy has not been started for this run. Add -Dtoxiproxy.enabled=true to the TEST run "
                            + "configuration's VM options (BaseTest then starts it before the health checks). "
                            + "See scripts/toxiproxy/README.md.");
        }
        try {
            Proxy proxy = client.getProxy(name);
            if (proxy == null) {
                throw new IllegalStateException(
                        "Toxiproxy admin API is reachable, but no proxy named '" + name + "' exists. "
                                + "If an already-running toxiproxy-server was reused, it was probably started "
                                + "without the config — stop it, or start it with -config " + CONFIG + "."
                                + serverLogHint());
            }
            return proxy;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Toxiproxy lookup of proxy '" + name + "' failed (" + e.getMessage() + "). Either the admin API at "
                            + ADMIN_HOST + ":" + ADMIN_PORT + " is unreachable, or the running toxiproxy-server "
                            + "doesn't have that proxy loaded — e.g. an instance left over from an earlier run, or "
                            + "started by hand without -config " + CONFIG + ". Inspect it with: "
                            + "curl -s http://" + ADMIN_HOST + ":" + ADMIN_PORT + "/proxies"
                            + serverLogHint(), e);
        }
    }

    /** Stops toxiproxy-server only if this run started it. Safe to call when nothing was started. */
    public static synchronized void stopIfOwned() {
        if (ownedProcess == null) {
            if (client != null) {
                log.info("🔌 Leaving Toxiproxy running — this run reused an already-running instance");
            }
            client = null;
            return;
        }

        log.info("🛑 Stopping toxiproxy-server (started by this run)...");
        ownedProcess.destroy();

        long deadline = System.currentTimeMillis() + 5000; // 5s grace period
        while (ownedProcess.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (ownedProcess.isAlive()) {
            log.warn("toxiproxy-server didn't stop within 5s — forcing termination");
            ownedProcess.destroyForcibly();
        }

        ownedProcess = null;
        client = null;
    }

    private static void startOwnedProcess() {
        log.info("🚀 Toxiproxy not running — starting toxiproxy-server (config: {}, server output: {})",
                CONFIG, SERVER_LOG);
        ProcessBuilder pb = new ProcessBuilder(BINARY, "-config", CONFIG.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(SERVER_LOG));
        try {
            ownedProcess = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start toxiproxy-server ('" + BINARY + "'). If it's not on PATH for this run, "
                            + "pass -Dtoxiproxy.binary=/full/path/to/toxiproxy-server. "
                            + "See scripts/toxiproxy/README.md.", e);
        }
        waitForAdminApiReady();
        log.info("✅ toxiproxy-server started by this run (PID {})", ownedProcess.pid());
    }

    private static void waitForAdminApiReady() {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (isAdminApiReachable()) {
                return;
            }
            if (!ownedProcess.isAlive()) {
                throw new IllegalStateException(
                        "toxiproxy-server exited immediately (exit code " + ownedProcess.exitValue()
                                + "). Check that the config file exists at " + CONFIG
                                + " and that its listen ports aren't already taken." + serverLogHint());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for toxiproxy-server to start", e);
            }
        }
        throw new IllegalStateException(
                "toxiproxy-server did not become ready within " + STARTUP_TIMEOUT.getSeconds() + "s of starting it");
    }

    /** Last lines of the server output when THIS run started it; empty string otherwise. */
    private static String serverLogHint() {
        if (ownedProcess == null) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(SERVER_LOG.toPath());
            int from = Math.max(0, lines.size() - 15);
            return "\ntoxiproxy-server output (" + SERVER_LOG + "), last lines:\n"
                    + String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "\n(could not read " + SERVER_LOG + ": " + e.getMessage() + ")";
        }
    }

    private static boolean isAdminApiReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ADMIN_HOST, ADMIN_PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}