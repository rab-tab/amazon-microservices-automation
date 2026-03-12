package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Amazon Microservices")
@Feature("User Management")
public class UserApiTest extends BaseTest {

    private String userToken;
    private String userId;
    private TestModels.RegisterRequest registeredUserData;

    @BeforeClass
    public void setup() {
        logStep("Creating test user for User API tests");
        registeredUserData = TestDataFactory.createRandomUser();
        TestModels.AuthResponse auth = AuthUtils.registerUser(registeredUserData);
        userToken = auth.getAccessToken();
        userId = auth.getUser().getId();
    }

    @Test(priority = 1)
    @Story("Get User Profile")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify authenticated user can retrieve their profile")
    public void testGetUserById() {
        logStep("Fetching user profile for userId: " + userId);

        Response response = given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", userId)
                .when()
                .get("/api/v1/users/{id}")
                .then()
                .statusCode(200)
                .body("id", equalTo(userId))
                .body("email", equalTo(registeredUserData.getEmail()))
                .body("username", equalTo(registeredUserData.getUsername()))
                .body("firstName", equalTo(registeredUserData.getFirstName()))
                .body("lastName", equalTo(registeredUserData.getLastName()))
                .body("role", equalTo("CUSTOMER"))
                .body("status", equalTo("ACTIVE"))
                .extract().response();

        TestModels.UserResponse user = response.as(TestModels.UserResponse.class);
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getEmail()).isEqualTo(registeredUserData.getEmail());
    }

    @Test(priority = 2)
    @Story("Get User Profile")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify 404 is returned for non-existent user")
    public void testGetNonExistentUser() {
        String fakeId = "00000000-0000-0000-0000-000000000000";

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", fakeId)
                .when()
                .get("/api/v1/users/{id}")
                .then()
                .statusCode(404)
                .body("message", containsString("not found"));
    }

    @Test(priority = 3)
    @Story("Update User Profile")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify user can update their own profile")
    public void testUpdateUserProfile() {
        String newFirstName = "UpdatedFirstName";
        String newLastName = "UpdatedLastName";

        TestModels.RegisterRequest updateReq = TestModels.RegisterRequest.builder()
                .firstName(newFirstName)
                .lastName(newLastName)
                .build();

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + userToken)
                .header("X-User-Id", userId)
                .pathParam("id", userId)
                .body(updateReq)
                .when()
                .put("/api/v1/users/{id}")
                .then()
                .statusCode(200)
                .body("firstName", equalTo(newFirstName))
                .body("lastName", equalTo(newLastName));
    }

    @Test(priority = 4)
    @Story("Update User Profile")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify user cannot update another user's profile")
    public void testUpdateOtherUserProfile() {
        String otherUserId = "00000000-0000-0000-0000-000000000001";
        TestModels.RegisterRequest updateReq = TestModels.RegisterRequest.builder()
                .firstName("Hacker")
                .build();

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + userToken)
                .header("X-User-Id", userId) // Different from path param
                .pathParam("id", otherUserId)
                .body(updateReq)
                .when()
                .put("/api/v1/users/{id}")
                .then()
                .statusCode(403);
    }

    @Test(priority = 5)
    @Story("Health Check")
    @Severity(SeverityLevel.MINOR)
    @Description("Verify user service health endpoint returns OK")
    public void testUserServiceHealth() {
        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .when()
                .get("/api/v1/users/health")
                .then()
                .statusCode(200);
    }
}
