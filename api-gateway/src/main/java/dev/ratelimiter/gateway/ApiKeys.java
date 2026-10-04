package dev.ratelimiter.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys are 256-bit random secrets, so a fast SHA-256 hash is sufficient for storage; unlike
 * user-chosen passwords they can't be guessed from a dictionary.
 */
public final class ApiKeys {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static boolean matches(String candidateApiKey, String storedHash) {
        if (candidateApiKey == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(candidateApiKey).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
