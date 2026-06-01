package com.amazon.tests.multithreadingFailureScenarios.revision;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

public class HttpClientPoolDemo {

    // ================================================================
    // SCENARIO 1: Default Apache client — the bug you saw
    // ================================================================
    static void scenario1_DefaultClient_TheBugYouSaw() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Default client — your exact error");
        System.out.println("=".repeat(60));

        // This is what RestAssured uses by default
        // BasicClientConnectionManager = ONE connection, not pooled at all
        // Under parallel execution → catastrophic

        System.out.println("""
            Default RestAssured uses BasicClientConnectionManager:
              maxPerRoute = 2
              maxTotal    = 20
            
            With thread-count=10, all hitting localhost:8080:
              Thread-1  gets connection  ✅
              Thread-2  gets connection  ✅
              Thread-3  WAITS...
              ...
              Thread-10 WAITS...
            
            Server closes the waiting connection after idle timeout:
              → "Premature end of chunk coded message body"
              → "Connection reset"
              → "SocketException: Connection closed"
            """);
    }

    // ================================================================
    // SCENARIO 2: Properly pooled HTTP client
    // ================================================================
    static CloseableHttpClient createPooledClient(int maxPerRoute,
                                                  int maxTotal,
                                                  int connectTimeout,
                                                  int socketTimeout) {
        // ✅ PoolingHttpClientConnectionManager — thread-safe connection pool
        PoolingHttpClientConnectionManager manager =
                new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(maxTotal);
        manager.setDefaultMaxPerRoute(maxPerRoute);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(socketTimeout)
                .setConnectionRequestTimeout(connectTimeout)
                .build();

        return HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    // ================================================================
    // SCENARIO 3: RestAssured with pooled client — the fix
    // ================================================================
    static RequestSpecification createThreadSafeSpec(String baseUrl,
                                                     int maxPerRoute,
                                                     int maxTotal) {
        PoolingHttpClientConnectionManager manager =
                new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(maxTotal);
        manager.setDefaultMaxPerRoute(maxPerRoute);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(manager)
                .build();

        // ✅ Inject pooled client into RestAssured
        return new RequestSpecBuilder()
                .setBaseUri(baseUrl)
                .setConfig(RestAssuredConfig.config()
                        .httpClient(HttpClientConfig.httpClientConfig()
                                .httpClientFactory(() -> httpClient)))
                .build();
    }

    // ================================================================
    // SCENARIO 4: ThreadLocal spec — one pooled client per thread
    // ================================================================
    static ThreadLocal<RequestSpecification> threadSpec =
            ThreadLocal.withInitial(() ->
                    createThreadSafeSpec("http://localhost:8080", 5, 20)
            );
}