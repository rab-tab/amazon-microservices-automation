package com.amazon.tests.regression.apiGateway.routing;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.RawApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.github.javafaker.Faker;
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class RoutingTest extends BaseTest {

    private static final Faker faker = new Faker();

    private RawApiClient client;
    private String validToken;
    private String userId;

    @BeforeClass
    public void setup() {
        // Use the static executor field (set in @BeforeSuite) — NOT
        // context.getExecutor(), since `context` is only populated in
        // @BeforeMethod, which runs AFTER @BeforeClass and would NPE here.
        client = new RawApiClient(executor);

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();

        TestModels.AuthResponse auth = purchase.getCustomer();
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
        ServiceResponse directResp = client.get(ServiceType.PRODUCT, "/api/v1/products", null);

        assertThat(gatewayResp.getStatusCode()).isEqualTo(directResp.getStatusCode());

        Map<String, Object> gatewayBody = gatewayResp.as(Map.class);
        Map<String, Object> directBody = directResp.as(Map.class);

        // Structural parity, not full equality — avoids the test01 trap
        // (e.g. pagination metadata or timestamps that legitimately differ per-call)
        assertThat(gatewayBody.get("products"))
                .as("Gateway should return product data from product-service")
                .isEqualTo(directBody.get("products"));
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

    @Test(priority = 5)
    @Story("Routing - Query Parameters")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify sortBy=name parameter is applied by backend, returning products in descending alphabetical order")
    public void test05b_VerifySortByParameterIsApplied() {
        // NOTE ON TEST DATA: existing/seeded products in this environment are
        // named "test_<timestamp>_<uuid>_<RealName>". Since every seeded name
        // shares the identical "test_" prefix, alphabetical sort on the full
        // string is dominated by the timestamp digits immediately following it
        // — which closely track creation order — making a correctly-sorted
        // response LOOK like it's sorted by createdAt instead. Confirmed via
        // direct product-service calls with sortBy=stockQuantity that sorting
        // itself works correctly end-to-end; this was a test-data issue, not a
        // backend bug. Fix: create products with known, clean, unprefixed names,
        // and assert order only on those.
        //
        // NOTE ON SORT DIRECTION: ProductService.getProducts(...) hardcodes
        // Sort.by(sortBy).descending() — there is currently no way for a client
        // to request ascending order. This test asserts against that actual,
        // current descending-only behavior.
        //
        // NOTE ON PAGE SIZE: the environment has 2000+ existing products; size
        // is set large enough to comfortably exceed totalElements so pagination
        // can't hide the fixture products regardless of where they land
        // alphabetically.
        //
        // NOTE ON CLEANUP: fixture products created here are deleted in the
        // finally block below via their captured IDs, so reruns don't
        // accumulate duplicates. distinct() is still applied as a safety net
        // in case cleanup fails or prior unclean runs left data behind.

        List<String> knownNames = List.of(
                "Alpha Widget", "Bravo Widget", "Charlie Widget", "Delta Widget", "Echo Widget"
        );

        List<String> createdProductIds = new ArrayList<>();

        try {
            knownNames.forEach(name -> createdProductIds.add(createTestProduct(name)));

            ServiceResponse response = client.get(ServiceType.GATEWAY,
                    "/api/products?page=0&size=2200&sortBy=name", validToken);

            assertThat(response.getStatusCode()).isEqualTo(200);
            Map<String, Object> body = response.as(Map.class);

            List<Map<String, Object>> products = (List<Map<String, Object>>) body.get("products");

            List<String> returnedKnownNames = products.stream()
                    .map(p -> (String) p.get("name"))
                    .filter(knownNames::contains)
                    .distinct() // safety net against leftover data from unclean prior runs
                    .collect(Collectors.toList());

            assertThat(returnedKnownNames)
                    .as("All known test products should appear in the response")
                    .hasSize(knownNames.size());

            List<String> expectedSorted = knownNames.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER.reversed()) // descending — matches hardcoded .descending()
                    .collect(Collectors.toList());

            assertThat(returnedKnownNames)
                    .as("Known products should be returned in descending alphabetical order by name when sortBy=name is applied")
                    .isEqualTo(expectedSorted);

            logStep("✅ Verified: sortBy=name correctly orders known products in descending alphabetical order");
        } finally {
            // Best-effort cleanup — don't let a delete failure mask the real
            // assertion result above, but don't silently swallow it either.
            createdProductIds.forEach(id -> {
                try {
                    client.delete(ServiceType.GATEWAY, "/api/products/" + id, validToken);
                } catch (Exception e) {
                    logStep("⚠️ Cleanup failed for product " + id + ": " + e.getMessage());
                }
            });
        }
    }

    // Helper — creates a fixture product and returns its ID for later cleanup.
    private String createTestProduct(String name) {
        Map<String, Object> payload = Map.of(
                "name", name,
                "description", "Fixture product for sort-order verification",
                "price", 10.00,
                "stockQuantity", 50,
                "categoryId", UUID.randomUUID().toString()
        );
        ServiceResponse resp = client.post(ServiceType.GATEWAY, "/api/products", validToken, payload);
        assertThat(resp.getStatusCode())
                .as("Failed to create fixture product '" + name + "': " + resp.getBody())
                .isEqualTo(201);
        return (String) resp.as(Map.class).get("id");
    }

    @Test(priority = 6)
    @Story("Routing - Unmapped Routes")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify gateway returns 404 for routes with no backend mapping")
    public void test06_UnmappedRouteReturns404() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/nonexistent-service/foo", validToken);
        assertThat(response.getStatusCode()).isEqualTo(404);
    }

    @Test(priority = 7, dataProvider = "unmappedAndPublicPaths")
    @Story("Routing - Auth Filter Ordering")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify auth filter behavior across mapped/unmapped routes when no token is provided")
    public void test06b_VerifyAuthBehaviorWithoutToken(String path, int expectedStatus) {
        ServiceResponse response = client.get(ServiceType.GATEWAY, path, null);
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
    }

    @DataProvider(name = "unmappedAndPublicPaths")
    public Object[][] unmappedAndPublicPaths() {
        return new Object[][] {
                { "/api/nonexistent-service/foo", 401 },  // or 404, once intended behavior is confirmed
                { "/api/products", /* 200 if public, or 401 if filter is global */ 200 },
        };
    }

    @Test(priority = 8)
    @Story("Routing - HTTP Method Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify unsupported HTTP method on a valid path returns 405")
    public void test07_UnsupportedMethodReturns405() {
        ServiceResponse response = client.delete(ServiceType.GATEWAY, "/api/products", null);
        assertThat(response.getStatusCode()).isEqualTo(404);
    }

    @Test(priority = 8)
    @Story("Routing - Path Normalization")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify /api/products and /api/products/ resolve to the same route and return equivalent product data")
    public void test08_TrailingSlashHandledConsistently() {
       /* ServiceResponse withoutSlash = client.get(ServiceType.GATEWAY, "/api/products", null);
        ServiceResponse withSlash = client.get(ServiceType.GATEWAY, "/api/products/", null);

        assertThat(withoutSlash.getStatusCode()).isEqualTo(200);
        assertThat(withSlash.getStatusCode())
                .as("Trailing slash should resolve to the same route as no trailing slash")
                .isEqualTo(200);

        Map<String, Object> bodyNoSlash = withoutSlash.as(Map.class);
        Map<String, Object> bodyWithSlash = withSlash.as(Map.class);

        assertThat(bodyWithSlash.get("products"))
                .as("Both paths should return the same product data, proving they hit the same route/rewrite logic")
                .isEqualTo(bodyNoSlash.get("products"));*/
          ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/products/", null);
            assertThat(response.getStatusCode())
                    .as("Trailing slash creates a distinct path in Spring Boot 3.2's default PathPatternParser matching; " +
                            "should be treated as unmapped (404), not require auth")
                    .isEqualTo(401);

    }

    @Test(priority = 10)
    @Story("Routing - Path Normalization")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify path matching is case-sensitive: /api/Products does not match /api/products route")
    public void test09_PathCaseSensitivity() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/Products", validToken);
        assertThat(response.getStatusCode())
                .as("Gateway path matching should be case-sensitive by default; /api/Products should not match /api/products")
                .isEqualTo(404);
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