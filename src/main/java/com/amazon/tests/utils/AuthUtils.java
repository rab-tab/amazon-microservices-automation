package com.amazon.tests.utils;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import io.restassured.response.Response;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import static io.restassured.RestAssured.given;

@UtilityClass
@Slf4j
public class AuthUtils {

    private static String cachedAdminToken;
    private static TestModels.UserResponse cachedAdminUser;

    /**
     * Register a user and return the auth response
     */
    public static TestModels.AuthResponse registerUser(TestModels.RegisterRequest request) {
        Response response = given().log().all()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(request)
                .when()
                .post("/api/v1/auth/register")
                .then().log().all()
                .statusCode(201)
                .extract()
                .response();

        return response.as(TestModels.AuthResponse.class);
    }

    /**
     * Login and get token
     */
    public static TestModels.AuthResponse login(String email, String password) {
        Response response = given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(TestModels.LoginRequest.builder()
                        .email(email)
                        .password(password)
                        .build())
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .response();

        return response.as(TestModels.AuthResponse.class);
    }

    /**
     * Register a new test user and return their token
     */
    public static String getNewUserToken() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
        TestModels.AuthResponse auth = registerUser(user);
        log.info("Created test user: {}", user.getEmail());
        return auth.getAccessToken();
    }

    /**
     * Register a new test user and return full auth info
     */
    public static TestModels.AuthResponse registerAndGetAuth() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
        return registerUser(user);
    }

    /**
     * Get admin token (cached)
     */
    public static String getAdminToken() {
        if (cachedAdminToken == null) {
            TestModels.RegisterRequest adminUser = TestModels.RegisterRequest.builder()
                    .username("testadmin_" + System.currentTimeMillis())
                    .email("admin_" + System.currentTimeMillis() + "@test.com")
                    .password("Admin@12345")
                    .firstName("Test")
                    .lastName("Admin")
                    .build();
            TestModels.AuthResponse auth = registerUser(adminUser);
            cachedAdminToken = auth.getAccessToken();
            cachedAdminUser = auth.getUser();
        }
        return cachedAdminToken;
    }
}
