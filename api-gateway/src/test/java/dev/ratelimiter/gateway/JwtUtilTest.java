package dev.ratelimiter.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jsonwebtoken.JwtException;

class JwtUtilTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void signsAndParsesHs256Token() {
        JwtUtil jwtUtil = new JwtUtil(properties("HS256", "test-secret-key-that-is-at-least-32-bytes-long"));

        String token = jwtUtil.generateToken("client-123", Set.of(Roles.CLIENT));

        assertEquals("client-123", jwtUtil.parseToken(token).getSubject());
    }

    @Test
    void embedsRolesClaim() {
        JwtUtil jwtUtil = new JwtUtil(properties("HS256", "test-secret-key-that-is-at-least-32-bytes-long"));

        String token = jwtUtil.generateToken("operator", Set.of(Roles.ADMIN));

        assertEquals(List.of(Roles.ADMIN), jwtUtil.parseToken(token).get(JwtUtil.ROLES_CLAIM, List.class));
    }

    @Test
    void rejectsTokenSignedWithDifferentHs256Secret() {
        JwtUtil issuer = new JwtUtil(properties("HS256", "test-secret-key-that-is-at-least-32-bytes-long"));
        JwtUtil verifier = new JwtUtil(properties("HS256", "another-secret-key-that-is-at-least-32-bytes-long"));

        String token = issuer.generateToken("client-123", Set.of(Roles.CLIENT));

        assertThrows(JwtException.class, () -> verifier.parseToken(token));
    }

    @Test
    void signsAndParsesRs256Token() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Path privateKeyPath = temporaryDirectory.resolve("private.pem");
        Path publicKeyPath = temporaryDirectory.resolve("public.pem");
        Files.writeString(privateKeyPath, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.writeString(publicKeyPath, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));

        JwtProperties properties = properties("RS256", "unused-secret-value-that-is-at-least-32-bytes-long");
        properties.setPrivateKeyPath(privateKeyPath.toString());
        properties.setPublicKeyPath(publicKeyPath.toString());
        JwtUtil jwtUtil = new JwtUtil(properties);

        String token = jwtUtil.generateToken("client-456", Set.of(Roles.CLIENT));

        assertEquals("client-456", jwtUtil.parseToken(token).getSubject());
    }

    private static JwtProperties properties(String algorithm, String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm(algorithm);
        properties.setSecret(secret);
        return properties;
    }

    private static String pem(String type, byte[] encodedKey) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encodedKey)
                + "\n-----END " + type + "-----\n";
    }
}