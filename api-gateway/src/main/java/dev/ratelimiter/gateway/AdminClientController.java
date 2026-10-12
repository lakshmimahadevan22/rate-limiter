package dev.ratelimiter.gateway;

import java.time.Instant;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Admin endpoints backing the {@code clients} table: dynamic, per-client rate-limit tiers
 * that {@link ClientTierResolver} looks up (via Redis cache) instead of the static YAML defaults.
 * Every change to a client evicts its cached tier so the new values apply on the next request.
 */
@RestController
@RequestMapping("/admin/clients")
public class AdminClientController {

    /** Matches the {@code client_id VARCHAR(100)} column and keeps IDs safe to use in Redis keys and URLs. */
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final ClientCredentialRepository repository;
    private final ClientTierResolver tierResolver;

    public AdminClientController(ClientCredentialRepository repository, ClientTierResolver tierResolver) {
        this.repository = repository;
        this.tierResolver = tierResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CreateClientResponse>> createClient(@RequestBody CreateClientRequest request) {
        if (request == null || request.clientId() == null || !CLIENT_ID_PATTERN.matcher(request.clientId()).matches()) {
            return Mono.error(badRequest(
                    "clientId is required and must be 1-100 characters of letters, digits, '.', '_' or '-'"));
        }
        if (request.capacity() <= 0 || request.refillTokens() <= 0 || request.refillPeriodMillis() <= 0) {
            return Mono.error(badRequest("capacity, refillTokens and refillPeriodMillis must all be positive"));
        }

        String apiKey = ApiKeys.generate();
        Instant now = Instant.now();
        ClientCredential credential = ClientCredential.createNew(
                request.clientId(),
                ApiKeys.hash(apiKey),
                request.capacity(),
                request.refillTokens(),
                request.refillPeriodMillis(),
                true,
                now,
                now);

        // The existence check gives a clean 409 in the common case; the constraint violation
        // catches two concurrent creates of the same ID racing past it.
        return repository.existsById(request.clientId())
                .flatMap(exists -> exists
                        ? Mono.error(alreadyExists(request.clientId()))
                        : repository.save(credential))
                .onErrorMap(DataIntegrityViolationException.class, exception -> alreadyExists(request.clientId()))
                .flatMap(saved -> tierResolver.invalidate(saved.getClientId()).thenReturn(saved))
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new CreateClientResponse(
                                saved.getClientId(),
                                apiKey,
                                saved.getRateLimitCapacity(),
                                saved.getRateLimitRefillTokens(),
                                saved.getRateLimitRefillPeriodMillis())));
    }

    @GetMapping
    public Flux<ClientView> listClients() {
        return repository.findAll().map(ClientView::of);
    }

    @GetMapping("/{clientId}")
    public Mono<ResponseEntity<ClientView>> getClient(@PathVariable String clientId) {
        return repository.findById(clientId)
                .map(credential -> ResponseEntity.ok(ClientView.of(credential)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Partially updates a client: any of the tier fields and the enabled flag may be supplied,
     * and omitted fields keep their current values. Setting {@code enabled} to true re-enables a
     * revoked client.
     */
    @PatchMapping("/{clientId}")
    public Mono<ResponseEntity<ClientView>> updateClient(
            @PathVariable String clientId,
            @RequestBody UpdateClientRequest request) {
        if (request == null) {
            return Mono.error(badRequest("request body is required"));
        }
        if (isNotPositive(request.capacity()) || isNotPositive(request.refillTokens())
                || (request.refillPeriodMillis() != null && request.refillPeriodMillis() <= 0)) {
            return Mono.error(badRequest("capacity, refillTokens and refillPeriodMillis must all be positive"));
        }

        return repository.findById(clientId)
                .flatMap(existing -> {
                    if (request.capacity() != null) {
                        existing.setRateLimitCapacity(request.capacity());
                    }
                    if (request.refillTokens() != null) {
                        existing.setRateLimitRefillTokens(request.refillTokens());
                    }
                    if (request.refillPeriodMillis() != null) {
                        existing.setRateLimitRefillPeriodMillis(request.refillPeriodMillis());
                    }
                    if (request.enabled() != null) {
                        existing.setEnabled(request.enabled());
                    }
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing);
                })
                .flatMap(saved -> tierResolver.invalidate(clientId).thenReturn(ResponseEntity.ok(ClientView.of(saved))))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /** Issues a new API key, immediately invalidating the old one for future logins. */
    @PostMapping("/{clientId}/api-key")
    public Mono<ResponseEntity<ApiKeyResponse>> rotateApiKey(@PathVariable String clientId) {
        String apiKey = ApiKeys.generate();
        return repository.findById(clientId)
                .flatMap(existing -> {
                    existing.setApiKeyHash(ApiKeys.hash(apiKey));
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing);
                })
                .map(saved -> ResponseEntity.ok(new ApiKeyResponse(saved.getClientId(), apiKey)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
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

    private static boolean isNotPositive(Integer value) {
        return value != null && value <= 0;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException alreadyExists(String clientId) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "client '" + clientId + "' already exists");
    }

    public record CreateClientRequest(String clientId, int capacity, int refillTokens, long refillPeriodMillis) {
    }

    public record CreateClientResponse(
            String clientId, String apiKey, int capacity, int refillTokens, long refillPeriodMillis) {
    }

    public record UpdateClientRequest(
            Integer capacity, Integer refillTokens, Long refillPeriodMillis, Boolean enabled) {
    }

    public record ApiKeyResponse(String clientId, String apiKey) {
    }

    public record ClientView(
            String clientId, int capacity, int refillTokens, long refillPeriodMillis, boolean enabled) {

        static ClientView of(ClientCredential credential) {
            return new ClientView(
                    credential.getClientId(),
                    credential.getRateLimitCapacity(),
                    credential.getRateLimitRefillTokens(),
                    credential.getRateLimitRefillPeriodMillis(),
                    credential.isEnabled());
        }
    }
}
