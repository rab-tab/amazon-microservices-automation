package com.amazon.tests.utils.metrics;


import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;

public class MetricsHttpServer {

    private HttpServer server;

    public void start() throws Exception {

        server =
                HttpServer.create(
                        new InetSocketAddress(9095),
                        0);

        server.createContext(
                "/metrics",
                exchange -> {

                    String response =
                            MetricsManager
                                    .prometheusRegistry()
                                    .scrape();

                    exchange.sendResponseHeaders(
                            200,
                            response.getBytes().length);

                    try (OutputStream os =
                                 exchange.getResponseBody()) {

                        os.write(response.getBytes());
                    }
                });

        server.start();

        System.out.println(
                "Prometheus endpoint started on port 9095");
    }

    public void stop() {

        if (server != null) {
            server.stop(0);
        }
    }
}