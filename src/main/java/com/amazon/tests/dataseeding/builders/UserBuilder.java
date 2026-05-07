// UserBuilder.java
package com.amazon.tests.dataseeding.builders;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.TestDataGenerator;
import lombok.extern.slf4j.Slf4j;

/**
 * Builder for User entities with Faker integration
 * Supports creating RegisterRequest DTOs for user registration
 *
 * Usage:
 * - UserBuilder.randomUser() - Quick random user
 * - UserBuilder.aUser().withEmail("test@example.com").build() - Custom user
 *
 * @author Amazon Automation Team
 */
@Slf4j
public class UserBuilder {

    private String namespace;
    private String username;
    private String email;
    private String password;
    private String firstName;
    private String lastName;
    private String phone;

    // Private constructor - use static factory methods
    private UserBuilder() {
    }

    // ==========================================
    // STATIC FACTORY METHODS
    // ==========================================

    /**
     * Create a builder instance
     *
     * Example:
     * UserBuilder.aUser()
     *     .withEmail("john@test.com")
     *     .withFirstName("John")
     *     .build();
     */
    public static UserBuilder aUser() {
        return new UserBuilder();
    }

    /**
     * Create a random user with all fields generated
     *
     * Example:
     * RegisterRequest user = UserBuilder.randomUser();
     */
    public static TestModels.RegisterRequest randomUser() {
        return new UserBuilder().build();
    }

    /**
     * Create a random user with namespace for test isolation
     *
     * Example:
     * RegisterRequest user = UserBuilder.randomUser("test_12345");
     * // Email: test_12345_john.smith@testdomain.com
     */
    public static TestModels.RegisterRequest randomUser(String namespace) {
        return new UserBuilder()
                .withNamespace(namespace)
                .build();
    }

    // ==========================================
    // BUILDER METHODS (Fluent API)
    // ==========================================

    /**
     * Set namespace for test isolation
     * Namespace will be prefixed to username and email
     *
     * Example:
     * .withNamespace("test_12345")
     * // Results in: test_12345_username, test_12345@testdomain.com
     */
    public UserBuilder withNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    /**
     * Set custom username
     *
     * Example:
     * .withUsername("johndoe123")
     */
    public UserBuilder withUsername(String username) {
        this.username = username;
        return this;
    }

    /**
     * Set custom email
     *
     * Example:
     * .withEmail("john.doe@test.com")
     */
    public UserBuilder withEmail(String email) {
        this.email = email;
        return this;
    }

    /**
     * Set custom password
     * If not set, generates secure password with special chars
     *
     * Example:
     * .withPassword("Test@12345")
     */
    public UserBuilder withPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * Set custom first name
     *
     * Example:
     * .withFirstName("John")
     */
    public UserBuilder withFirstName(String firstName) {
        this.firstName = firstName;
        return this;
    }

    /**
     * Set custom last name
     *
     * Example:
     * .withLastName("Doe")
     */
    public UserBuilder withLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    /**
     * Set custom phone number
     *
     * Example:
     * .withPhone("+1234567890")
     */
    public UserBuilder withPhone(String phone) {
        this.phone = phone;
        return this;
    }

    /**
     * Set full name (first + last)
     *
     * Example:
     * .withFullName("John", "Doe")
     */
    public UserBuilder withFullName(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
        return this;
    }

    /**
     * Generate email based on first and last name
     *
     * Example:
     * .withFirstName("John").withLastName("Doe").withEmailFromName()
     * // Results in: john.doe.12345@testdomain.com
     */
    public UserBuilder withEmailFromName() {
        if (firstName != null && lastName != null) {
            this.email = TestDataGenerator.generateEmail(firstName, lastName);
        }
        return this;
    }

    /**
     * Use Indian phone number format
     *
     * Example:
     * .withIndianPhone()
     * // Results in: +91 9876543210
     */
    public UserBuilder withIndianPhone() {
        this.phone = TestDataGenerator.generateIndianPhoneNumber();
        return this;
    }

    // ==========================================
    // BUILD METHOD
    // ==========================================

    /**
     * Build the RegisterRequest with all configured values
     * Missing fields are filled with random data from Faker
     *
     * @return RegisterRequest ready for API call
     */
    public TestModels.RegisterRequest build() {
        // Generate final email with namespace if provided
        String finalEmail = buildEmail();

        // Generate final username with namespace if provided
        String finalUsername = buildUsername();

        // Generate other fields with defaults if not set
        String finalPassword = password != null ? password : TestDataGenerator.generatePassword();
        String finalFirstName = firstName != null ? firstName : TestDataGenerator.generateFirstName();
        String finalLastName = lastName != null ? lastName : TestDataGenerator.generateLastName();
        String finalPhone = phone != null ? phone : TestDataGenerator.generateIndianPhoneNumber();

        log.debug("Building user: email={}, username={}", finalEmail, finalUsername);

        return TestModels.RegisterRequest.builder()
                .username(finalUsername)
                .email(finalEmail)
                .password(finalPassword)
                .firstName(finalFirstName)
                .lastName(finalLastName)
                .phone(finalPhone)
                .build();
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private String buildEmail() {
        String baseEmail;

        if (email != null) {
            baseEmail = email;
        } else if (firstName != null && lastName != null) {
            // Generate from name
            baseEmail = TestDataGenerator.generateEmail(firstName, lastName);
        } else {
            // Generate random
            baseEmail = TestDataGenerator.generateEmail();
        }

        // Add namespace prefix if provided
        if (namespace != null && !baseEmail.contains(namespace)) {
            // Replace the part before @ with namespace_part
            String[] parts = baseEmail.split("@");
            return namespace + "_" + parts[0] + "@" + parts[1];
        }

        return baseEmail;
    }

    private String buildUsername() {
        String baseUsername = username != null ? username : TestDataGenerator.generateUsername();

        // Add namespace prefix if provided
        if (namespace != null && !baseUsername.contains(namespace)) {
            return namespace + "_" + baseUsername;
        }

        return baseUsername;
    }
}