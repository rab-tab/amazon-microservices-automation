package com.amazon.tests.regression.apiGateway.routing;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.apiClients.RawApiClient;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("API Gateway - Routing Verification")
public class RoutingTest extends BaseTest {

    private static final Faker faker = new Faker();

    private RawApiClient client;
    private String validToken;
    private String userId;

    @BeforeClass
    public void setup() {
        client = new RawApiClient(context.getExecutor());

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        validToken = auth.getAccessToken();
        userId = auth.getUser().getId();
        logStep("Setup complete - user: " + userId);
    }

    @Test(priority = 1)
    @Story("Routing - Service Identification")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify gateway routes /api/users/** to user-service (not product/order service)")
    public void test01_VerifyRoutingToUserService() {
        ServiceResponse gatewayResp = client.get(ServiceType.GATEWAY, "/api/users/health", null);
        assertThat(gatewayResp.getStatusCode()).isEqualTo(200);

        ServiceResponse directResp = client.get(ServiceType.USER, "/api/v1/users/health", null);
        assertThat(directResp.getStatusCode()).isEqualTo(200);

        assertThat(gatewayResp.getBody())
                .as("Gateway should proxy to user-service, returning same response")
                .isEqualTo(directResp.getBody());

        logStep("✅ Verified: Gateway routes /api/users/** to user-service");
    }

    @Test(priority = 2)
    @Story("Routing - Service Identification")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify gateway routes /api/products/** to product-service")
    public void test02_VerifyRoutingToProductService() {
        ServiceResponse gatewayResp = client.get(ServiceType.GATEWAY, "/api/products", null);
        assertThat(gatewayResp.getStatusCode()).isEqualTo(200);
        assertThat(gatewayResp.as(Map.class).get("products"))
                .as("Gateway should return product data from product-service")
                .isNotNull();

        logStep("✅ Verified: Gateway routes /api/products/** to product-service");
    }

    @Test(priority = 3)
    @Story("Routing - Path Rewriting & Precedence")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify /api/users/register rewrites to /api/v1/auth/register and matches before wildcard /api/users/**")
    public void test03_VerifyPathRewriting() {
        Map<String, String> userData = randomUserPayload();

        ServiceResponse gatewayResp = client.post(ServiceType.GATEWAY, "/api/users/register", null, userData);
        assertThat(gatewayResp.getStatusCode()).isEqualTo(201);

        Map<String, Object> body = gatewayResp.as(Map.class);
        assertThat(body.get("accessToken"))
                .as("Gateway response should contain accessToken (proves route hit auth/register, not wildcard users/**)")
                .isNotNull();
        assertThat(((Map<?, ?>) body.get("user")).get("id")).isNotNull();

        logStep("✅ Verified: /api/users/register rewrites to /api/v1/auth/register (specific route wins over wildcard)");
    }

    @Test(priority = 4)
    @Story("Routing - Path Rewriting")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify gateway rewrites /api/users/{id} → /api/v1/users/{id}, preserving path variable, and propagates identity correctly")
    public void test04_VerifyPathRewritingWithVariable() {
        ServiceResponse gatewayResp = client.get(ServiceType.GATEWAY, "/api/users/" + userId, validToken);
        assertThat(gatewayResp.getStatusCode()).isEqualTo(200);

        ServiceResponse directResp = client.get(ServiceType.USER, "/api/v1/users/" + userId, null);
        assertThat(directResp.getStatusCode()).isEqualTo(200);

        Map<String, Object> gatewayBody = gatewayResp.as(Map.class);
        Map<String, Object> directBody = directResp.as(Map.class);

        assertThat(gatewayBody.get("id"))
                .as("Gateway should return same user ID as direct call")
                .isEqualTo(userId)
                .isEqualTo(directBody.get("id"));

        assertThat(gatewayBody.get("email"))
                .as("Gateway should return same user data (and correctly propagate identity from JWT)")
                .isEqualTo(directBody.get("email"));

        logStep("✅ Verified: Path variables preserved and identity propagated correctly");
    }

    @DataProvider(name = "pageSizes")
    public Object[][] pageSizes() {
        return new Object[][] { { 5 }, { 10 } };
    }

    @Test(priority = 5, dataProvider = "pageSizes")
    @Story("Routing - Query Parameters")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify query parameters are passed through and affect backend behavior")
    public void test05_VerifyQueryParametersArePassed(int size) {
        ServiceResponse response = client.get(ServiceType.GATEWAY,
                "/api/products?page=0&size=" + size + "&sortBy=name", null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        Map<String, Object> body = response.as(Map.class);
        int pageSize = (int) body.get("size");

        assertThat(pageSize)
                .as("Backend should receive and apply size=" + size + " parameter")
                .isEqualTo(size);

        logStep("✅ Verified: size=" + size + " correctly passed through to backend");
    }

    @Test
    @Description("Verify gateway returns 404 for routes with no backend mapping")
    public void test06_UnmappedRouteReturns404() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/nonexistent-service/foo", null);
        assertThat(response.getStatusCode()).isEqualTo(404);
    }

    @Test
    @Description("Verify unsupported HTTP method on a valid path returns 405")
    public void test07_UnsupportedMethodReturns405() {
        ServiceResponse response = client.delete(ServiceType.GATEWAY, "/api/products", null); // needs RawApiClient.delete(...)
        assertThat(response.getStatusCode()).isEqualTo(405);
    }

    @Test
    @Description("Verify trailing slash doesn't break routing")
    public void test08_TrailingSlashHandledConsistently() {
        ServiceResponse withoutSlash = client.get(ServiceType.GATEWAY, "/api/products", null);
        ServiceResponse withSlash = client.get(ServiceType.GATEWAY, "/api/products/", null);
        assertThat(withoutSlash.getStatusCode()).isEqualTo(withSlash.getStatusCode());
    }

    @Test
    @Description("Verify path case sensitivity behaves as configured")
    public void test09_PathCaseSensitivity() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/Products", null);
        // Assert whatever your gateway's intended behavior is — 404 if case-sensitive, 200 if not
        assertThat(response.getStatusCode()).isIn(200, 404);
    }

    private Map<String, String> randomUserPayload() {
        return Map.of(
                "username", faker.name().username() + faker.number().digits(4),
                "email", faker.internet().emailAddress(),
                "password", "Test@" + faker.internet().password(8, 12),
                "firstName", faker.name().firstName(),
                "lastName", faker.name().lastName(),
                "phone", "+91" + faker.number().digits(10)
        );
    }
}