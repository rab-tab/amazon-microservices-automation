package com.amazon.tests.utils;


import net.datafaker.Faker;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * Thread-Safe Test Data Generator for Amazon Microservices Automation
 *
 * Uses Datafaker library for realistic test data generation.
 * All methods are thread-safe for parallel test execution.
 *
 * Features:
 * - User data (names, emails, phones)
 * - Address data (US and Indian addresses)
 * - Product data (SKUs, names, prices)
 * - Order data (IDs, statuses, quantities)
 * - Payment data (credit cards, expiry dates)
 * - Business data (companies, job titles)
 * - Date/time generation
 *
 * @author Amazon Automation Team
 * @version 1.0
 */
public class TestDataGenerator {

    // Thread-local Faker instances for thread safety in parallel execution
    private static final ThreadLocal<Faker> defaultFaker =
            ThreadLocal.withInitial(() -> new Faker());

    private static final ThreadLocal<Faker> usFaker =
            ThreadLocal.withInitial(() -> new Faker(Locale.US));

    private static final ThreadLocal<Faker> indianFaker =
            ThreadLocal.withInitial(() -> new Faker(new Locale("en", "IND")));

    // ==========================================
    // USER DATA GENERATION
    // ==========================================

    /**
     * Generate unique user ID with thread and timestamp
     * Format: user_<threadId>_<timestamp>
     *
     * Example: user_15_1714567890123
     */
    public static String generateUserId() {
        long threadId = Thread.currentThread().getId();
        long timestamp = System.currentTimeMillis();
        return String.format("user_%d_%d", threadId, timestamp);
    }

    /**
     * Generate realistic first name
     *
     * Example: "John", "Emma", "Raj"
     */
    public static String generateFirstName() {
        return defaultFaker.get().name().firstName();
    }

    /**
     * Generate realistic last name
     *
     * Example: "Smith", "Johnson", "Patel"
     */
    public static String generateLastName() {
        return defaultFaker.get().name().lastName();
    }

    /**
     * Generate full name (first + last)
     *
     * Example: "John Smith"
     */
    public static String generateFullName() {
        return defaultFaker.get().name().fullName();
    }

    /**
     * Generate unique email address with timestamp
     * Format: <username>_<timestamp>@testdomain.com
     *
     * Example: john.smith_7890@testdomain.com
     */
    public static String generateEmail() {
        String username = defaultFaker.get().name().username().toLowerCase();
        long timestamp = System.currentTimeMillis();
        return String.format("%s_%d@testdomain.com", username, timestamp % 10000);
    }

    /**
     * Generate email based on first and last name
     * Format: <firstname>.<lastname>.<timestamp>@testdomain.com
     *
     * Example: john.smith.7890@testdomain.com
     */
    public static String generateEmail(String firstName, String lastName) {
        long timestamp = System.currentTimeMillis();
        return String.format("%s.%s.%d@testdomain.com",
                firstName.toLowerCase(),
                lastName.toLowerCase(),
                timestamp % 10000);
    }

    /**
     * Generate US phone number
     *
     * Example: "(555) 123-4567"
     */
    public static String generatePhoneNumber() {
        return usFaker.get().phoneNumber().phoneNumber();
    }

    /**
     * Generate Indian phone number
     *
     * Example: "+91 9876543210"
     */
    public static String generateIndianPhoneNumber() {
        return indianFaker.get().phoneNumber().phoneNumber();
    }

    /**
     * Generate secure password with mix of characters
     *
     * @param minLength minimum length
     * @param maxLength maximum length
     * @param includeUppercase include uppercase letters
     * @param includeSpecial include special characters
     * @param includeDigit include digits
     *
     * Example: "P@ssw0rd123"
     */
    public static String generatePassword(int minLength, int maxLength,
                                          boolean includeUppercase,
                                          boolean includeSpecial,
                                          boolean includeDigit) {
        return defaultFaker.get().internet().password(
                minLength, maxLength, includeUppercase, includeSpecial, includeDigit
        );
    }

    /**
     * Generate default password (8-16 chars with all character types)
     *
     * Example: "Test@1234"
     */
    public static String generatePassword() {
        return generatePassword(8, 16, true, true, true);
    }

    /**
     * Generate username
     *
     * Example: "john.smith123"
     */
    public static String generateUsername() {
        return defaultFaker.get().name().username();
    }

    // ==========================================
    // ADDRESS DATA GENERATION
    // ==========================================

    /**
     * Generate US street address
     *
     * Example: "123 Main Street"
     */
    public static String generateStreetAddress() {
        return usFaker.get().address().streetAddress();
    }

    /**
     * Generate US city
     *
     * Example: "Seattle"
     */
    public static String generateCity() {
        return usFaker.get().address().city();
    }

    /**
     * Generate US state
     *
     * Example: "Washington"
     */
    public static String generateState() {
        return usFaker.get().address().state();
    }

    /**
     * Generate US state abbreviation
     *
     * Example: "WA"
     */
    public static String generateStateAbbr() {
        return usFaker.get().address().stateAbbr();
    }

    /**
     * Generate US zip code
     *
     * Example: "98101"
     */
    public static String generateZipCode() {
        return usFaker.get().address().zipCode();
    }

    /**
     * Generate country name
     *
     * Example: "United States"
     */
    public static String generateCountry() {
        return usFaker.get().address().country();
    }

    /**
     * Generate complete US address
     * Format: <street>, <city>, <state> <zip>, <country>
     *
     * Example: "123 Main St, Seattle, WA 98101, United States"
     */
    public static String generateCompleteAddress() {
        return String.format("%s, %s, %s %s, %s",
                generateStreetAddress(),
                generateCity(),
                generateState(),
                generateZipCode(),
                "United States"
        );
    }

    /**
     * Generate complete Indian address
     *
     * Example: "12/34 MG Road, Bangalore, Karnataka - 560001, India"
     */
    public static String generateIndianAddress() {
        Faker faker = indianFaker.get();
        return String.format("%s, %s, %s - %s, India",
                faker.address().streetAddress(),
                faker.address().city(),
                faker.address().state(),
                faker.address().zipCode()
        );
    }

    // ==========================================
    // PRODUCT DATA GENERATION
    // ==========================================

    /**
     * Generate unique product SKU
     * Format: <CATEGORY>-<timestamp>-<random>
     *
     * Example: "ELEC-12345-6789"
     */
    public static String generateProductSku() {
        String prefix = pickRandom("ELEC", "FASH", "HOME", "BOOK", "FOOD", "TOYS", "SPRT");
        long timestamp = System.currentTimeMillis();
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return String.format("%s-%d-%d", prefix, timestamp % 100000, random);
    }

    /**
     * Generate realistic product name
     *
     * Example: "Ergonomic Steel Shoes"
     */
    public static String generateProductName() {
        return defaultFaker.get().commerce().productName();
    }

    /**
     * Generate product description
     *
     * Example: "A wonderful serenity has taken possession..."
     */
    public static String generateProductDescription() {
        return defaultFaker.get().lorem().sentence(15);
    }

    /**
     * Generate product price in specified range
     *
     * @param min minimum price
     * @param max maximum price
     * @return price rounded to 2 decimal places
     *
     * Example: 45.99
     */
    public static double generateProductPrice(double min, double max) {
        double price = ThreadLocalRandom.current().nextDouble(min, max);
        return Math.round(price * 100.0) / 100.0;  // Round to 2 decimals
    }

    /**
     * Generate default product price ($10 - $100)
     *
     * Example: 67.45
     */
    public static double generateProductPrice() {
        return generateProductPrice(10.0, 100.0);
    }

    /**
     * Generate product category
     *
     * Example: "Electronics"
     */
    public static String generateProductCategory() {
        return defaultFaker.get().commerce().department();
    }

    /**
     * Generate product brand/company name
     *
     * Example: "TechCorp Industries"
     */
    public static String generateProductBrand() {
        return defaultFaker.get().company().name();
    }

    /**
     * Generate product color
     *
     * Example: "Red"
     */
    public static String generateProductColor() {
        return defaultFaker.get().color().name();
    }

    /**
     * Generate product material
     *
     * Example: "Steel", "Plastic", "Wood"
     */
    public static String generateProductMaterial() {
        return defaultFaker.get().commerce().material();
    }

    // ==========================================
    // ORDER DATA GENERATION
    // ==========================================

    /**
     * Generate unique order ID
     * Format: ORD-<10-digit-number>
     *
     * Example: "ORD-1234567890"
     */
    public static String generateOrderId() {
        return "ORD-" + defaultFaker.get().number().digits(10);
    }

    /**
     * Generate order quantity in range
     *
     * @param min minimum quantity
     * @param max maximum quantity
     *
     * Example: 3
     */
    public static int generateOrderQuantity(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Generate default order quantity (1-5)
     *
     * Example: 2
     */
    public static int generateOrderQuantity() {
        return generateOrderQuantity(1, 5);
    }

    /**
     * Generate order status
     *
     * Options: PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED
     *
     * Example: "CONFIRMED"
     */
    public static String generateOrderStatus() {
        return pickRandom(
                "PENDING", "CONFIRMED", "PROCESSING",
                "SHIPPED", "DELIVERED", "CANCELLED"
        );
    }

    /**
     * Generate tracking number
     * Format: 1Z<16-alphanumeric>
     *
     * Example: "1Z999AA10123456784"
     */
    public static String generateTrackingNumber() {
        return "1Z" + defaultFaker.get().number().digits(16);
    }

    // ==========================================
    // PAYMENT DATA GENERATION
    // ==========================================

    /**
     * Generate test credit card number
     * Returns common test card numbers (Visa, Mastercard, Amex, Discover)
     *
     * WARNING: These are TEST cards only, do not use in production!
     *
     * Example: "4111111111111111" (Visa test card)
     */
    public static String generateCreditCardNumber() {
        return pickRandom(
                "4111111111111111",  // Visa test card
                "5500000000000004",  // Mastercard test card
                "340000000000009",   // Amex test card
                "6011000000000004"   // Discover test card
        );
    }

    /**
     * Generate credit card type
     *
     * Example: "Visa"
     */
    public static String generateCreditCardType() {
        return pickRandom("Visa", "Mastercard", "American Express", "Discover");
    }

    /**
     * Generate CVV/CVC code
     *
     * Example: "123"
     */
    public static String generateCVV() {
        return defaultFaker.get().number().digits(3);
    }

    /**
     * Generate future expiry date
     * Format: MM/YYYY
     *
     * Example: "06/2027"
     */
    public static String generateExpiryDate() {
        int month = ThreadLocalRandom.current().nextInt(1, 13);
        int year = ThreadLocalRandom.current().nextInt(2025, 2030);
        return String.format("%02d/%d", month, year);
    }

    /**
     * Generate cardholder name
     *
     * Example: "JOHN SMITH"
     */
    public static String generateCardholderName() {
        return generateFullName().toUpperCase();
    }

    // ==========================================
    // BUSINESS DATA GENERATION
    // ==========================================

    /**
     * Generate company name
     *
     * Example: "TechCorp Industries"
     */
    public static String generateCompanyName() {
        return defaultFaker.get().company().name();
    }

    /**
     * Generate job title
     *
     * Example: "Senior Software Engineer"
     */
    public static String generateJobTitle() {
        return defaultFaker.get().job().title();
    }

    /**
     * Generate department
     *
     * Example: "Engineering"
     */
    public static String generateDepartment() {
        return pickRandom(
                "Engineering", "Sales", "Marketing", "HR",
                "Finance", "Operations", "Customer Support"
        );
    }

    // ==========================================
    // INTERNET DATA GENERATION
    // ==========================================

    /**
     * Generate domain name
     *
     * Example: "example.com"
     */
    public static String generateDomainName() {
        return defaultFaker.get().internet().domainName();
    }

    /**
     * Generate URL
     *
     * Example: "https://www.example.com"
     */
    public static String generateUrl() {
        return defaultFaker.get().internet().url();
    }

    /**
     * Generate IPv4 address
     *
     * Example: "192.168.1.1"
     */
    public static String generateIpAddress() {
        return defaultFaker.get().internet().ipV4Address();
    }

    /**
     * Generate UUID
     *
     * Example: "550e8400-e29b-41d4-a716-446655440000"
     */
    public static String generateUUID() {
        return java.util.UUID.randomUUID().toString();
    }

    // ==========================================
    // DATE/TIME GENERATION
    // ==========================================

    /**
     * Generate past date within specified days
     *
     * @param days number of days in the past
     *
     * Example: 30 days ago
     */
    public static Date generatePastDate(int days) {

        LocalDate futureDate = LocalDate.now().plusDays(days);
        return Date.from(futureDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Generate future date within specified days
     *
     * @param days number of days in the future
     *
     * Example: 30 days from now
     */
    public static Date generateFutureDate(int days) {
        LocalDate pastDate = LocalDate.now().minusDays(days);
        return Date.from(pastDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }


    /**
     * Generate birthday for person of specified age range
     *
     * @param minAge minimum age
     * @param maxAge maximum age
     *
     * Example: Birthday for someone 25-35 years old
     */
    public static Date generateBirthday(int minAge, int maxAge) {
        return defaultFaker.get().date().birthday(minAge, maxAge);
    }

    /**
     * Generate birthday for adult (18-65)
     *
     * Example: Birthday for someone 18-65 years old
     */
    public static Date generateBirthday() {
        return generateBirthday(18, 65);
    }

    /**
     * Generate LocalDate in the past
     *
     * @param days number of days in the past
     */
    public static LocalDate generatePastLocalDate(int days) {
        Date date = generatePastDate(days);
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Generate LocalDate in the future
     *
     * @param days number of days in the future
     */
    public static LocalDate generateFutureLocalDate(int days) {
        Date date = generateFutureDate(days);
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    // ==========================================
    // TEXT GENERATION
    // ==========================================

    /**
     * Generate random sentence
     *
     * Example: "Lorem ipsum dolor sit amet."
     */
    public static String generateSentence() {
        return defaultFaker.get().lorem().sentence();
    }

    /**
     * Generate random sentence with word count
     *
     * @param wordCount number of words
     */
    public static String generateSentence(int wordCount) {
        return defaultFaker.get().lorem().sentence(wordCount);
    }

    /**
     * Generate random paragraph
     *
     * Example: "Lorem ipsum dolor sit amet, consectetur adipiscing elit..."
     */
    public static String generateParagraph() {
        return defaultFaker.get().lorem().paragraph();
    }

    /**
     * Generate random word
     *
     * Example: "lorem"
     */
    public static String generateWord() {
        return defaultFaker.get().lorem().word();
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    /**
     * Generate random integer in range (inclusive)
     *
     * @param min minimum value
     * @param max maximum value
     *
     * Example: generateRandomNumber(1, 100) -> 42
     */
    public static int generateRandomNumber(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Generate random double in range
     *
     * @param min minimum value
     * @param max maximum value
     *
     * Example: generateRandomDouble(1.0, 10.0) -> 5.67
     */
    public static double generateRandomDouble(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    /**
     * Pick random option from provided values
     *
     * @param options array of options
     * @return randomly selected option
     *
     * Example: pickRandom("A", "B", "C") -> "B"
     */
    @SafeVarargs
    public static <T> T pickRandom(T... options) {
        if (options == null || options.length == 0) {
            throw new IllegalArgumentException("Options cannot be null or empty");
        }
        int index = ThreadLocalRandom.current().nextInt(options.length);
        return options[index];
    }

    /**
     * Generate boolean with specified probability of being true
     *
     * @param probabilityOfTrue probability (0.0 to 1.0)
     *
     * Example: generateBoolean(0.7) -> 70% chance of true
     */
    public static boolean generateBoolean(double probabilityOfTrue) {
        return ThreadLocalRandom.current().nextDouble() < probabilityOfTrue;
    }

    /**
     * Generate boolean (50/50 chance)
     *
     * Example: generateBoolean() -> true or false
     */
    public static boolean generateBoolean() {
        return generateBoolean(0.5);
    }
}
