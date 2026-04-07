package com.amazon.tests.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Test Utilities
 *
 * Helper methods for JWT security testing.
 * Used to create various types of invalid/tampered tokens for security tests.
 *
 * IMPORTANT: These are for TESTING ONLY!
 * These methods intentionally create invalid JWTs to test security.
 *
 * This version does NOT require io.jsonwebtoken dependency.
 * It only uses Jackson for JSON manipulation.
 */
@UtilityClass
@Slf4j
public class JwtTestUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ══════════════════════════════════════════════════════════════════════════
    // DECODE/ENCODE UTILITIES
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Decode JWT payload (without verifying signature)
     */
    public static Map<String, Object> decodePayload(String token) {
        try {
            String[] parts = token.split("\\.");
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JWT payload", e);
        }
    }

    /**
     * Encode payload back to JWT format (without signing)
     */
    private static String encodePayload(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode payload", e);
        }
    }

    /**
     * Extract header
     */
    public static Map<String, Object> decodeHeader(String token) {
        try {
            String[] parts = token.split("\\.");
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            return objectMapper.readValue(headerJson, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JWT header", e);
        }
    }

    /**
     * Encode header
     */
    private static String encodeHeader(Map<String, Object> header) {
        try {
            String json = objectMapper.writeValueAsString(header);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode header", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLAIM TAMPERING ATTACKS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Modify a specific claim (tampering attack)
     * This breaks the signature, making the token invalid
     */
    public static String modifyClaim(String token, String key, Object newValue) {
        try {
            String[] parts = token.split("\\.");

            Map<String, Object> payload = decodePayload(token);
            payload.put(key, newValue);

            String newPayload = encodePayload(payload);

            // Keep original header and signature (signature now invalid)
            return parts[0] + "." + newPayload + "." + parts[2];

        } catch (Exception e) {
            throw new RuntimeException("Failed to modify claim", e);
        }
    }

    /**
     * Add a new claim to the token (breaks signature)
     */
    public static String addClaim(String token, String key, Object value) {
        return modifyClaim(token, key, value);
    }

    /**
     * Remove a claim from the token (breaks signature)
     */
    public static String removeClaim(String token, String key) {
        Map<String, Object> payload = decodePayload(token);
        payload.remove(key);

        return rebuildToken(token, payload);
    }

    /**
     * Change role (privilege escalation attack)
     */
    public static String escalateRole(String token, String newRole) {
        return modifyClaim(token, "role", newRole);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNATURE ATTACKS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Remove signature completely (alg=none attack)
     * Changes algorithm to "none" and removes signature
     */
    public static String createUnsignedToken(String token) {
        try {
            Map<String, Object> header = decodeHeader(token);
            header.put("alg", "none");

            String encodedHeader = encodeHeader(header);
            String payload = token.split("\\.")[1];

            return encodedHeader + "." + payload + ".";
        } catch (Exception e) {
            throw new RuntimeException("Failed to create unsigned token", e);
        }
    }

    /**
     * Create a fresh unsigned token with alg=none
     * This creates a new token without signature
     */
    public static String createUnsignedToken(String userId, String email) {
        try {
            Map<String, Object> header = new HashMap<>();
            header.put("alg", "none");
            header.put("typ", "JWT");

            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", userId);
            payload.put("email", email);
            payload.put("iat", System.currentTimeMillis() / 1000);
            payload.put("exp", (System.currentTimeMillis() / 1000) + 86400);

            String encodedHeader = encodeHeader(header);
            String encodedPayload = encodePayload(payload);

            // No signature for alg=none
            return encodedHeader + "." + encodedPayload + ".";
        } catch (Exception e) {
            log.error("Failed to create unsigned token", e);
            throw new RuntimeException("Failed to create unsigned token", e);
        }
    }

    /**
     * Tamper with the signature directly
     * Corrupts the signature while keeping header and payload intact
     */
    public static String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        // Corrupt the signature by changing last 5 characters
        String tamperedSignature = parts[2].substring(0, Math.max(0, parts[2].length() - 5)) + "XXXXX";

        return parts[0] + "." + parts[1] + "." + tamperedSignature;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPIRATION ATTACKS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Expire token by modifying exp claim
     * Sets expiration to 1 hour in the past
     */
    public static String expireToken(String token) {
        Map<String, Object> payload = decodePayload(token);
        payload.put("exp", System.currentTimeMillis() / 1000 - 3600); // expired 1 hour ago

        return rebuildToken(token, payload);
    }

    /**
     * Set token expiration to future (long-lived token attack)
     * Attempts to extend token lifetime (breaks signature)
     */
    public static String extendExpiration(String token, long futureMillis) {
        Map<String, Object> payload = decodePayload(token);
        payload.put("exp", (System.currentTimeMillis() + futureMillis) / 1000);

        return rebuildToken(token, payload);
    }

    /**
     * Create a token that's already expired
     * Uses existing token as template and modifies expiration
     * NOTE: This is just expireToken() but creates a fresh expired token
     */
    public static String createExpiredToken(String userId, String email) {
        // Create a basic unsigned token structure
        Map<String, Object> header = new HashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", userId);
        payload.put("email", email);
        payload.put("iat", (System.currentTimeMillis() / 1000) - 7200); // 2 hours ago
        payload.put("exp", (System.currentTimeMillis() / 1000) - 3600); // 1 hour ago (expired)

        String encodedHeader = encodeHeader(header);
        String encodedPayload = encodePayload(payload);

        // Invalid signature (all zeros)
        String fakeSignature = "INVALID_SIGNATURE_EXPIRED_TOKEN";

        return encodedHeader + "." + encodedPayload + "." + fakeSignature;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TOKEN CORRUPTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build malformed token (random corruption)
     * Corrupts the end of the token
     */
    public static String corruptToken(String token) {
        return token.substring(0, Math.max(0, token.length() - 5)) + "abcde";
    }

    /**
     * Completely invalid token (not even valid JWT format)
     */
    public static String randomGarbageToken() {
        return "invalid.jwt.token";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Helper to rebuild token with modified payload (keeps original signature → invalid)
     */
    private static String rebuildToken(String token, Map<String, Object> payload) {
        String[] parts = token.split("\\.");
        String newPayload = encodePayload(payload);

        return parts[0] + "." + newPayload + "." + parts[2];
    }

    /**
     * Print JWT claims for debugging
     */
    public static void printClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                log.warn("Invalid JWT format");
                return;
            }

            String header = new String(Base64.getUrlDecoder().decode(parts[0]));
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

            log.info("=== JWT Header ===");
            log.info(header);
            log.info("=== JWT Payload ===");
            log.info(payload);
            if (parts.length > 2) {
                log.info("=== JWT Signature (first 50 chars) ===");
                log.info(parts[2].substring(0, Math.min(50, parts[2].length())));
            }
        } catch (Exception e) {
            log.error("Failed to decode JWT", e);
        }
    }

    /**
     * Validate token format (basic check, doesn't verify signature)
     */
    public static boolean isValidFormat(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        String[] parts = token.split("\\.");
        return parts.length == 3;
    }
}