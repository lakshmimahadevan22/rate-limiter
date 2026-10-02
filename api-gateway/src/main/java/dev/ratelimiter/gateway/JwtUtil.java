package dev.ratelimiter.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {

    private final JwtProperties properties;
    private final SecretKey secretKey;
    private final PrivateKey privateKey;
    private final JwtParser parser;
    private final boolean rsa;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        if (properties.getExpiration() == null || properties.getExpiration().isZero()
                || properties.getExpiration().isNegative()) {
            throw new IllegalArgumentException("JWT expiration must be positive");
        }

        String algorithm = properties.getAlgorithm().toUpperCase(Locale.ROOT);
        if ("HS256".equals(algorithm)) {
            this.rsa = false;
            this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
            this.privateKey = null;
            this.parser = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.getIssuer())
                    .build();
        } else if ("RS256".equals(algorithm)) {
            this.rsa = true;
            this.secretKey = null;
            this.privateKey = readPrivateKey(properties.getPrivateKeyPath());
            PublicKey publicKey = readPublicKey(properties.getPublicKeyPath());
            this.parser = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(properties.getIssuer())
                    .build();
        } else {
            throw new IllegalArgumentException("JWT algorithm must be HS256 or RS256");
        }
    }

    public String generateToken(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT subject must not be blank");
        }

        Instant issuedAt = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(properties.getExpiration())));

        if (rsa) {
            return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        }
        return builder.signWith(secretKey, Jwts.SIG.HS256).compact();
    }

    public Claims parseToken(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    private static PrivateKey readPrivateKey(String path) {
        try {
            byte[] keyBytes = decodePem(path);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Unable to load JWT RS256 private key", exception);
        }
    }

    private static PublicKey readPublicKey(String path) {
        try {
            byte[] keyBytes = decodePem(path);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Unable to load JWT RS256 public key", exception);
        }
    }

    private static byte[] decodePem(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Both RS256 key paths must be configured");
        }
        String pem = Files.readString(Path.of(path));
        String encoded = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }
}