package com.amazon.tests.utils.kafka;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BrokerController {

    private static final Map<Integer, Integer> BROKER_PORTS = Map.of(
            1, 9093,
            2, 9094,
            3, 9095
    );

    private static final Path DATA_DIR = Path.of(
            System.getProperty("user.home"),
            "kafka-data",
            "insync-cluster"
    );

    private static final Path CONFIG_DIR =
            DATA_DIR.resolve("config");

    private static final Path LOG_DIR =
            DATA_DIR.resolve("logs");

    private static Path kafkaHome = null;

    public BrokerController() {
        String configuredHome = System.getProperty(
                "kafka.home",
                System.getenv("KAFKA_HOME")
        );

        if (configuredHome == null || configuredHome.isBlank()) {
            throw new IllegalStateException(
                    "Set -Dkafka.home or the KAFKA_HOME environment variable"
            );
        }

        this.kafkaHome = Path.of(configuredHome)
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(kafkaHome.resolve("bin"))) {
            throw new IllegalStateException(
                    "Kafka bin directory not found: " + kafkaHome
            );
        }
    }

    public static void stopBroker(int brokerId) throws Exception {
        int port = getPort(brokerId);
        Path config = getConfigPath(brokerId);

        ProcessHandle process = findBrokerProcess(config)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot find running Kafka process for broker "
                                + brokerId + " using config " + config
                ));

        System.out.println("Stopping Kafka broker " + brokerId
                + " (PID: " + process.pid() + ")");

        // Sends a graceful termination signal.
        process.destroy();

        waitUntil(() -> !process.isAlive(),
                Duration.ofSeconds(30),
                "Broker " + brokerId + " process did not stop");

        waitUntil(() -> !isPortOpen(port),
                Duration.ofSeconds(15),
                "Broker " + brokerId + " port did not close: " + port);

        System.out.println("Broker " + brokerId + " stopped.");
    }

    public static void startBroker(int brokerId) throws Exception {
        int port = getPort(brokerId);
        Path config = getConfigPath(brokerId);

        if (!Files.isRegularFile(config)) {
            throw new IllegalStateException(
                    "Broker config does not exist: " + config
            );
        }

        if (findBrokerProcess(config).isPresent()) {
            throw new IllegalStateException(
                    "Broker " + brokerId + " is already running"
            );
        }

        if (isPortOpen(port)) {
            throw new IllegalStateException(
                    "Port " + port + " is already occupied"
            );
        }

        Path startScript =
                kafkaHome.resolve("bin/kafka-server-start.sh");

        if (!Files.isExecutable(startScript)) {
            throw new IllegalStateException(
                    "Kafka start script is not executable: " + startScript
            );
        }

        Files.createDirectories(LOG_DIR);

        Path logFile = LOG_DIR.resolve(
                "broker-" + brokerId + ".log"
        );

        System.out.println("Starting Kafka broker " + brokerId);

        new ProcessBuilder(
                startScript.toString(),
                config.toString()
        )
                .redirectErrorStream(true)
                .redirectOutput(
                        ProcessBuilder.Redirect.appendTo(logFile.toFile())
                )
                .start();

        waitUntil(
                () -> findBrokerProcess(config).isPresent()
                        && isPortOpen(port),
                Duration.ofSeconds(60),
                "Broker " + brokerId + " did not start. Check " + logFile
        );

        System.out.println("Broker " + brokerId + " started.");
    }

    public boolean isBrokerRunning(int brokerId) {
        Path config = getConfigPath(brokerId);
        return findBrokerProcess(config).isPresent();
    }

    private static Optional<ProcessHandle> findBrokerProcess(Path config) {
        String expectedConfig = config.toAbsolutePath()
                .normalize()
                .toString();

        List<ProcessHandle> matches = ProcessHandle.allProcesses()
                .filter(process -> {
                    String commandLine = process.info()
                            .commandLine()
                            .orElse("");

                    return commandLine.contains("kafka.Kafka")
                            && commandLine.contains(expectedConfig);
                })
                .toList();

        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Multiple Kafka processes found for " + expectedConfig
            );
        }

        return matches.stream().findFirst();
    }

    private static Path getConfigPath(int brokerId) {
        getPort(brokerId); // Validate broker ID.

        return CONFIG_DIR.resolve(
                "broker-" + brokerId + ".properties"
        ).toAbsolutePath().normalize();
    }

    private static int getPort(int brokerId) {
        Integer port = BROKER_PORTS.get(brokerId);

        if (port == null) {
            throw new IllegalArgumentException(
                    "Unknown broker ID: " + brokerId
            );
        }

        return port;
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress("127.0.0.1", port),
                    500
            );
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void waitUntil(
            java.util.function.BooleanSupplier condition,
            Duration timeout,
            String failureMessage
    ) throws InterruptedException {

        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            Thread.sleep(250);
        }

        throw new IllegalStateException(failureMessage);
    }
}

