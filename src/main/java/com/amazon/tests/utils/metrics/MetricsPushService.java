package com.amazon.tests.utils.metrics;



import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Slf4j
public final class MetricsPushService {

    private static final String PUSHGATEWAY =
            System.getProperty(
                    "pushgateway.url",
                    "http://localhost:9091");

    private MetricsPushService() {
    }

    public static void pushToPrometheus(String suiteName) {

        try {

            PrometheusMeterRegistry registry =
                    MetricsManager.prometheusRegistry();

            String metrics =
                    registry.scrape();

            String endpoint =
                    PUSHGATEWAY
                            + "/metrics/job/"
                            + suiteName;

            URL url = new URL(endpoint);

            HttpURLConnection connection =
                    (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);

            try (OutputStream os =
                         connection.getOutputStream()) {

                os.write(metrics.getBytes());
            }

            int responseCode =
                    connection.getResponseCode();

            if (responseCode >= 200
                    && responseCode < 300) {

                log.info(
                        "✅ Metrics pushed successfully to PushGateway. job={}",
                        suiteName);

            } else {

                log.error(
                        "❌ Failed pushing metrics. responseCode={}",
                        responseCode);
            }

        } catch (Exception e) {

            log.error(
                    "❌ Error pushing metrics",
                    e);
        }
    }

    public static void deleteJob(String suiteName) {

        try {

            URL url =
                    new URL(
                            PUSHGATEWAY
                                    + "/metrics/job/"
                                    + suiteName);

            HttpURLConnection connection =
                    (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("DELETE");

            int responseCode =
                    connection.getResponseCode();

            log.info(
                    "Deleted PushGateway job={} response={}",
                    suiteName,
                    responseCode);

        } catch (Exception e) {

            log.warn(
                    "Unable to delete PushGateway job={}",
                    suiteName,
                    e);
        }
    }
}
