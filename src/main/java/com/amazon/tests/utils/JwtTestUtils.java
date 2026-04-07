package com.amazon.tests.utils;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@UtilityClass
public class JwtTestUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

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

    /**
     * Modify a specific claim (tampering attack)
     */
    public static String modifyClaim(String token, String key, Object newValue) {
        try {
            String[] parts = token.split("\\.");

            Map<String, Object> payload = decodePayload(token);
            payload.put(key, newValue);

            String newPayload = encodePayload(payload);

            // Keep original header, drop signature (invalid token)
            return parts[0] + "." + newPayload + "." + parts[2];

        } catch (Exception e) {
            throw new RuntimeException("Failed to modify claim", e);
        }
    }

    /**
     * Remove signature completely (alg=none attack)
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
     * Expire token by modifying exp claim
     */
    public static String expireToken(String token) {
        Map<String, Object> payload = decodePayload(token);
        payload.put("exp", System.currentTimeMillis() / 1000 - 3600); // expired 1 hour ago

        return rebuildToken(token, payload);
    }

    /**
     * Set token expiration to future (long-lived token attack)
     */
    public static String extendExpiration(String token, long futureMillis) {
        Map<String, Object> payload = decodePayload(token);
        payload.put("exp", (System.currentTimeMillis() + futureMillis) / 1000);

        return rebuildToken(token, payload);
    }

    /**
     * Change role (privilege escalation)
     */
    public static String escalateRole(String token, String newRole) {
        return modifyClaim(token, "role", newRole);
    }

    /**
     * Remove a claim
     */
    public static String removeClaim(String token, String key) {
        Map<String, Object> payload = decodePayload(token);
        payload.remove(key);

        return rebuildToken(token, payload);
    }

    /**
     * Build malformed token (random corruption)
     */
    public static String corruptToken(String token) {
        return token.substring(0, token.length() - 5) + "abcde";
    }

    /**
     * Completely invalid token
     */
    public static String randomGarbageToken() {
        return "invalid.jwt.token";
    }

    /**
     * Helper to rebuild token with modified payload (keeps signature → invalid)
     */
    private static String rebuildToken(String token, Map<String, Object> payload) {
        String[] parts = token.split("\\.");
        String newPayload = encodePayload(payload);

        return parts[0] + "." + newPayload + "." + parts[2];
    }
}
