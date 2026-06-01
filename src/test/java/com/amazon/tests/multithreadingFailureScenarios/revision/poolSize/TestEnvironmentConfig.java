package com.amazon.tests.multithreadingFailureScenarios.revision.poolSize;

package com.amazon.tests.config;

import org.aeonbits.owner.Config;
import org.aeonbits.owner.ConfigFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/**
 * Single source of truth for all test environment configuration.
 *
 * Priority order (highest to lowest):
 *   1. System properties  (-Dbase.url=...)
 *   2. Environment variables (BASE_URL=...)
 *   3. application-test.yml
 *   4. Hardcoded defaults (local dev only)
 *
 * Usage:
 *   TestEnvironmentConfig env = new TestEnvironmentConfig();
 *   env.getBaseUrl()
 *   env.getUsersDbUrl()
 *   env.getDbUsername()
 */
public class TestEnvironmentConfig {

    // ── Environment enum ─────────────────────────────────────────────
    public enum Environment {
        LOCAL, CI, STAGING;

        public static Environment from(String value) {
            if (value == null) return LOCAL;
            return switch (value.toLowerCase().trim()) {
                case "ci", "jenkins", "github" -> CI;
                case "staging", "stage"        -> STAGING;
                default                        -> LOCAL;
            };
        }
    }

    // ── Raw config loaded from YAML ──────────────────────────────────
    private final Map<String, Object> config;
    private final Environment         environment;

    // ── Constructor ──────────────────────────────────────────────────
    public TestEnvironmentConfig() {
        this.config      = loadYaml();
        this.environment = Environment.from(
                resolve("TEST_ENVIRONMENT",
                        "test.environment",
                        get("test.environment"),
                        "local")
        );

        validateRequiredProperties();
    }

    // ── YAML Loading ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml() {
        String profile = System.getProperty("spring.profiles.active",
                System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE",
                        "test"));

        String filename = "application-" + profile + ".yml";

        try (InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream(filename)) {

            if (is == null) {
                System.out.println("⚠️  " + filename + " not found,"
                        + " using defaults");
                return Map.of();
            }

            Yaml yaml = new Yaml();
            Map<String, Object> raw = yaml.load(is);
            return raw != null ? flatten(raw, "") : Map.of();

        } catch (Exception e) {
            System.err.println("Failed to load " + filename
                    + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Flattens nested YAML into dot-notation keys.
     * test:
     *   base-url: http://...
     * becomes: "test.base-url" -> "http://..."
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(Map<String, Object> map,
                                        String prefix) {
        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        map.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                result.putAll(flatten((Map<String, Object>) value, fullKey));
            } else {
                result.put(fullKey, value);
            }
        });
        return result;
    }

    // ── Resolution priority ──────────────────────────────────────────

    /**
     * Resolves a value with priority:
     *   System property → Env var → YAML → default
     */
    private String resolve(String envVarName,
                           String sysPropName,
                           Object yamlValue,
                           String defaultValue) {
        // 1. System property (-Dbase.url=...)
        String sysProp = System.getProperty(sysPropName);
        if (sysProp != null && !sysProp.isBlank()) return sysProp;

        // 2. Environment variable
        String envVar = System.getenv(envVarName);
        if (envVar != null && !envVar.isBlank()) return envVar;

        // 3. YAML value
        if (yamlValue != null) {
            String yaml = yamlValue.toString();
            // Resolve ${ENV_VAR:default} placeholders
            return resolvePlaceholder(yaml);
        }

        // 4. Default
        return defaultValue;
    }

    /**
     * Resolves ${ENV_VAR:defaultValue} placeholders in YAML values.
     */
    private String resolvePlaceholder(String value) {
        if (value == null || !value.startsWith("${")) return value;

        // Extract: ${DB_PASSWORD:amazon123}
        String inner   = value.substring(2, value.length() - 1);
        String[] parts = inner.split(":", 2);
        String envVar  = parts[0];
        String fallback = parts.length > 1 ? parts[1] : null;

        String resolved = System.getenv(envVar);
        if (resolved != null) return resolved;
        if (fallback != null) return fallback;

        throw new ConfigurationException(
                "Required env var not set: " + envVar);
    }

    private Object get(String key) {
        return config.get(key);
    }

    // ── Validation ───────────────────────────────────────────────────

    private void validateRequiredProperties() {
        // These must always be resolvable
        String[] required = {
                "test.database.username",
                "test.database.password"
        };

        StringBuilder missing = new StringBuilder();
        for (String key : required) {
            try {
                String value = resolve(
                        key.replace(".", "_").toUpperCase(),
                        key,
                        get(key),
                        null
                );
                if (value == null) {
                    missing.append("\n  - ").append(key);
                }
            } catch (Exception e) {
                missing.append("\n  - ").append(key)
                        .append(" (").append(e.getMessage()).append(")");
            }
        }

        if (!missing.isEmpty()) {
            throw new ConfigurationException(
                    "Required configuration missing:" + missing
                            + "\n\nSet these in:"
                            + "\n  application-test.yml"
                            + "\n  or as -D system properties"
                            + "\n  or as environment variables"
            );
        }
    }

    // ================================================================
    // PUBLIC API — typed getters used by SharedPoolManager, BaseTest etc.
    // ================================================================

    // ── Environment ──────────────────────────────────────────────────

    public Environment getEnvironment() {
        return environment;
    }

    public boolean isLocal()   { return environment == Environment.LOCAL; }
    public boolean isCI()      { return environment == Environment.CI; }
    public boolean isStaging() { return environment == Environment.STAGING; }

    // ── URLs ─────────────────────────────────────────────────────────

    public String getBaseUrl() {
        return resolve(
                "BASE_URL",
                "base.url",
                get("test.base-url"),
                "http://localhost:8080"
        );
    }

    public String getUserServiceUrl() {
        return resolve(
                "USER_SERVICE_URL",
                "user.service.url",
                get("test.services.user-service"),
                "http://localhost:8081"
        );
    }

    public String getProductServiceUrl() {
        return resolve(
                "PRODUCT_SERVICE_URL",
                "product.service.url",
                get("test.services.product-service"),
                "http://localhost:8082"
        );
    }

    public String getOrderServiceUrl() {
        return resolve(
                "ORDER_SERVICE_URL",
                "order.service.url",
                get("test.services.order-service"),
                "http://localhost:8083"
        );
    }

    public String getPaymentServiceUrl() {
        return resolve(
                "PAYMENT_SERVICE_URL",
                "payment.service.url",
                get("test.services.payment-service"),
                "http://localhost:8084"
        );
    }

    // ── Database credentials ─────────────────────────────────────────

    public String getDbUsername() {
        return resolve(
                "DB_USERNAME",
                "test.database.username",
                get("test.database.username"),
                "amazon"
        );
    }

    public String getDbPassword() {
        return resolve(
                "DB_PASSWORD",
                "test.database.password",
                get("test.database.password"),
                null // no default — must be set
        );
    }

    public String getDbHost() {
        return resolve(
                "DB_HOST",
                "test.database.host",
                get("test.database.host"),
                "localhost"
        );
    }

    public int getDbPort() {
        String port = resolve(
                "DB_PORT",
                "test.database.port",
                get("test.database.port"),
                "5432"
        );
        return Integer.parseInt(port);
    }

    // ── JDBC URLs — built from components ────────────────────────────

    public String getUsersDbUrl() {
        return buildJdbcUrl(getDbName("users_db"));
    }

    public String getProductsDbUrl() {
        return buildJdbcUrl(getDbName("products_db"));
    }

    public String getOrdersDbUrl() {
        return buildJdbcUrl(getDbName("orders_db"));
    }

    public String getPaymentsDbUrl() {
        return buildJdbcUrl(getDbName("payments_db"));
    }

    private String getDbName(String poolKey) {
        return resolve(
                poolKey.toUpperCase() + "_NAME",
                "test.database.databases." + poolKey,
                get("test.database.databases." + poolKey),
                poolKey // db name same as pool key by default
        );
    }

    private String buildJdbcUrl(String dbName) {
        return String.format(
                "jdbc:postgresql://%s:%d/%s",
                getDbHost(), getDbPort(), dbName
        );
    }

    // ── Pool overrides — SDETs can force specific sizes if needed ────

    /**
     * Returns 0 if auto-calculate (default).
     * Returns N if SDET explicitly set max-connections-override.
     * Used by PoolSizeCalculator to respect manual overrides.
     */
    public int getDbPoolSizeOverride() {
        String override = resolve(
                "DB_POOL_SIZE_OVERRIDE",
                "test.database.pool.max-connections-override",
                get("test.database.pool.max-connections-override"),
                "0"
        );
        return Integer.parseInt(override);
    }

    public int getHttpSocketTimeoutOverride() {
        String override = resolve(
                "HTTP_SOCKET_TIMEOUT_MS",
                "test.http-client.socket-timeout-override-ms",
                get("test.http-client.socket-timeout-override-ms"),
                "0"
        );
        return Integer.parseInt(override);
    }

    // ── Other framework config ────────────────────────────────────────

    public int getIdempotencyTtlSeconds() {
        String ttl = resolve(
                "IDEMPOTENCY_TTL_SECONDS",
                "test.idempotency.ttl-seconds",
                get("test.idempotency.ttl-seconds"),
                "5"
        );
        return Integer.parseInt(ttl);
    }

    public String getSpringProfile() {
        return resolve(
                "SPRING_PROFILES_ACTIVE",
                "spring.profiles.active",
                get("test.spring.profile"),
                "test"
        );
    }

    // ── Print loaded config (safe — no passwords) ────────────────────

    public void printConfig() {
        System.out.printf("""
            ┌────────────────────────────────────────────────────────┐
            │ Configuration Details                                  │
            ├────────────────────────────────────────────────────────┤
            │ Environment     : %-36s│
            │ Base URL        : %-36s│
            │ User Service    : %-36s│
            │ Product Service : %-36s│
            │ Order Service   : %-36s│
            │ Payment Service : %-36s│
            ├────────────────────────────────────────────────────────┤
            │ DB Host         : %-36s│
            │ DB Port         : %-36d│
            │ DB Username     : %-36s│
            │ DB Password     : %-36s│
            ├────────────────────────────────────────────────────────┤
            │ Users DB URL    : %-36s│
            │ Products DB URL : %-36s│
            │ Orders DB URL   : %-36s│
            │ Payments DB URL : %-36s│
            ├────────────────────────────────────────────────────────┤
            │ Spring Profile  : %-36s│
            │ Idempotency TTL : %-33ds│
            │ Pool Override   : %-36s│
            └────────────────────────────────────────────────────────┘
            %n""",
                environment,
                getBaseUrl(),
                getUserServiceUrl(),
                getProductServiceUrl(),
                getOrderServiceUrl(),
                getPaymentServiceUrl(),
                getDbHost(),
                getDbPort(),
                getDbUsername(),
                "***masked***",
                getUsersDbUrl(),
                getProductsDbUrl(),
                getOrdersDbUrl(),
                getPaymentsDbUrl(),
                getSpringProfile(),
                getIdempotencyTtlSeconds(),
                getDbPoolSizeOverride() == 0
                        ? "auto (derived from threads)"
                        : String.valueOf(getDbPoolSizeOverride())
        );
    }

    // ── Custom exception ─────────────────────────────────────────────

    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }
    }
}
