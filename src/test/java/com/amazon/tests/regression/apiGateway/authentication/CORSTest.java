package com.amazon.tests.regression.apiGateway.authentication;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.apiClients.GatewayApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class CORSTest extends BaseTest {

    private GatewayApiClient gatewayApiClient() {
        return new GatewayApiClient(context.getExecutor());
    }

    @Test(priority = 30)
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify API Gateway handles CORS preflight requests correctly")
    @Story("Security - CORS Configuration")
    public void test30_CORSPreflightHandling() {
        logStep("Testing CORS preflight handling");

        Map<String, String> preflightHeaders = Map.of(
                "Origin", "http://localhost:3000",
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "Content-Type,Authorization"
        );

        ServiceResponse response = gatewayApiClient().options("/api/users/register", preflightHeaders);

        logStep("OPTIONS response: HTTP " + response.getStatusCode());

        assertThat(response.getStatusCode()).isIn(200, 204)
                .describedAs("CORS preflight should return 200 OK or 204 No Content");

        String allowOrigin = response.getHeaders().get("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isNotNull()
                .describedAs("Access-Control-Allow-Origin header must be present");
        assertThat(allowOrigin).isIn("*", "http://localhost:3000")
                .describedAs("Should allow localhost:3000 or all origins");
        logStep("✓ Access-Control-Allow-Origin: " + allowOrigin);

        String allowMethods = response.getHeaders().get("Access-Control-Allow-Methods");
        assertThat(allowMethods).isNotNull()
                .describedAs("Access-Control-Allow-Methods header must be present");
        assertThat(allowMethods.toUpperCase()).contains("POST")
                .describedAs("Must allow POST method");
        logStep("✓ Access-Control-Allow-Methods: " + allowMethods);

        String allowHeaders = response.getHeaders().get("Access-Control-Allow-Headers");
        assertThat(allowHeaders).isNotNull()
                .describedAs("Access-Control-Allow-Headers must be present");
        assertThat(allowHeaders.toLowerCase())
                .contains("content-type")
                .contains("authorization")
                .describedAs("Must allow Content-Type and Authorization headers");
        logStep("✓ Access-Control-Allow-Headers: " + allowHeaders);

        String maxAge = response.getHeaders().get("Access-Control-Max-Age");
        if (maxAge != null) {
            assertThat(Integer.parseInt(maxAge)).isGreaterThan(0)
                    .describedAs("Max-Age should be positive");
            logStep("✓ Access-Control-Max-Age: " + maxAge + " seconds");
        }

        logStep("✅ CORS preflight handling works correctly");
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify custom headers are propagated through API Gateway to backend service")
    @Story("Gateway - Header Propagation")
    public void test31_CustomHeaderPropagation() {
        logStep("TEST 31: Verifying custom header propagation");

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();

        String requestId = "test-request-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "test-correlation-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = auth.getUser().getId();

        logStep("Test headers:");
        logStep("  X-Request-ID: " + requestId);
        logStep("  X-Correlation-ID: " + correlationId);
        logStep("  X-User-Id: " + userId);

        Map<String, String> customHeaders = Map.of(
                "X-User-Id", userId,
                "X-Request-ID", requestId,
                "X-Correlation-ID", correlationId
        );

        ServiceResponse response = gatewayApiClient()
                .get("/api/orders/test/echo-headers", auth.getAccessToken(), customHeaders);

        logStep("Response status: HTTP " + response.getStatusCode());

        assertThat(response.getStatusCode()).isEqualTo(200)
                .describedAs("Echo headers endpoint should return 200 OK");

        Map<String, Object> receivedHeaders = response.as(Map.class);

        logStep("Headers received by backend:");
        receivedHeaders.forEach((key, value) -> logStep("  " + key + ": " + value));

        assertThat(receivedHeaders.get("x-request-id"))
                .as("X-Request-ID should be propagated")
                .isNotNull()
                .isEqualTo(requestId);
        logStep("✓ X-Request-ID propagated correctly");

        assertThat(receivedHeaders.get("x-correlation-id"))
                .as("X-Correlation-ID should be propagated")
                .isNotNull()
                .isEqualTo(correlationId);
        logStep("✓ X-Correlation-ID propagated correctly");

        assertThat(receivedHeaders.get("x-user-id"))
                .as("X-User-Id should be propagated")
                .isNotNull()
                .isEqualTo(userId);
        logStep("✓ X-User-Id propagated correctly");

        logStep("✅ All custom headers propagated correctly through gateway");
    }
}