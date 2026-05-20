package com.amazon.tests;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class DockerTest {
    public static void main(String[] args) {
        // ⭐ Rancher Desktop socket for user 'rabia'
        String rancherSocket = "/Users/rabia/.rd/docker.sock";

        System.out.println("🔍 Using Rancher socket: " + rancherSocket);

        System.setProperty("DOCKER_HOST", "unix://" + rancherSocket);
        System.setProperty("testcontainers.docker.socket.override", rancherSocket);
        System.setProperty("testcontainers.docker.client.strategy",
                "org.testcontainers.dockerclient.UnixSocketClientProviderStrategy");

        try {
            System.out.println("🐳 Starting Kafka container...");

            KafkaContainer kafka = new KafkaContainer(
                    DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
            );

            kafka.start();
            System.out.println("✅ Kafka started: " + kafka.getBootstrapServers());

            Thread.sleep(2000);

            kafka.stop();
            System.out.println("✅ Kafka stopped successfully");

        } catch (Exception e) {
            System.err.println("❌ Failed");
            e.printStackTrace();
        }
    }
}
