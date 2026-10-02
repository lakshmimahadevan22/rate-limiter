package dev.ratelimiter.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Admin endpoints backing the {@code clients} table: dynamic, per-client rate-limit tiers
 * that {@link ClientTierResolver} looks up (via Redis cache) instead of the static YAML defaults.
 */
@RestController
@RequestMapping("/admin/clients")
public class AdminClientController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ClientCredentialRepository repository;
    private final ClientTierResolver tierResolver;

    public AdminClientController(ClientCredentialRepository repository, ClientTierResolver tierResolver) {
        this.repository = repository;
        this.tierResolver = tierResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CreateClientResponse>> createClient(@RequestBody CreateClientRequest request) {
        if (request == null || request.clientId() == null || request.clientId().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        if (request.capacity() <= 0 || request.refillTokens() <= 0 || request.refillPeriodMillis() <= 0) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        String apiKey = generateApiKey();
        Instant now = Instant.now();
        ClientCredential credential = new ClientCredential(
                request.clientId(),
                hash(apiKey),
                request.capacity(),
                request.refillTokens(),
                request.refillPeriodMillis(),
                true,
                now,
                now);

        return repository.save(credential)
                .flatMap(saved -> tierResolver.invalidate(saved.getClientId()).thenReturn(saved))
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new CreateClientResponse(
                                saved.getClientId(),
                                apiKey,
                                saved.getRateLimitCapacity(),
                                saved.getRateLimitRefillTokens(),
                                saved.getRateLimitRefillPeriodMillis())));
    }

    @DeleteMapping("/{clientId}")
    public Mono<ResponseEntity<Void>> revokeClient(@PathVariable String clientId) {
        return repository.findById(clientId)
                .flatMap(existing -> {
                    existing.setEnabled(false);
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing);
                })
                .flatMap(saved -> tierResolver.invalidate(clientId)
                        .thenReturn(ResponseEntity.noContent().<Void>build()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/{clientId}")
    public Mono<ResponseEntity<ClientView>> getClient(@PathVariable String clientId) {
        return repository.findById(clientId)
                .map(credential -> ResponseEntity.ok(new ClientView(
                        credential.getClientId(),
                        credential.getRateLimitCapacity(),
                        credential.getRateLimitRefillTokens(),
                        credential.getRateLimitRefillPeriodMillis(),
                        credential.isEnabled())))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private static String generateApiKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record CreateClientRequest(String clientId, int capacity, int refillTokens, long refillPeriodMillis) {
    }

    public record CreateClientResponse(
            String clientId, String apiKey, int capacity, int refillTokens, long refillPeriodMillis) {
    }

    public record ClientView(
            String clientId, int capacity, int refillTokens, long refillPeriodMillis, boolean enabled) {
    }
}
