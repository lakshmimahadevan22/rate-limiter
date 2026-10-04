package dev.ratelimiter.gateway;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final JwtUtil jwtUtil;
    private final ClientCredentialRepository clientRepository;

    public AuthController(
            AuthProperties authProperties,
            JwtProperties jwtProperties,
            JwtUtil jwtUtil,
            ClientCredentialRepository clientRepository) {
        this.authProperties = authProperties;
        this.jwtProperties = jwtProperties;
        this.jwtUtil = jwtUtil;
        this.clientRepository = clientRepository;
    }

    /** Operator login: the single configured username/password receives an admin token. */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> issueToken(@RequestBody TokenRequest request) {
        if (request == null || !authProperties.matches(request.username(), request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(tokenFor(request.username(), Roles.ADMIN));
    }

    /**
     * Client login: exchanges a client ID and the API key issued by {@code POST /admin/clients} for
     * a token whose subject is the client ID, so that client's rate-limit tier applies. Unknown
     * clients, wrong keys and revoked clients all receive the same 401, so callers can't use this
     * endpoint to discover which client IDs exist.
     */
    @PostMapping("/client-token")
    public Mono<ResponseEntity<TokenResponse>> issueClientToken(@RequestBody ClientTokenRequest request) {
        if (request == null || request.clientId() == null || request.clientId().isBlank()
                || request.apiKey() == null || request.apiKey().isBlank()) {
            return Mono.just(unauthorized());
        }
        return clientRepository.findById(request.clientId())
                .filter(ClientCredential::isEnabled)
                .filter(client -> ApiKeys.matches(request.apiKey(), client.getApiKeyHash()))
                .map(client -> ResponseEntity.ok(tokenFor(client.getClientId(), Roles.CLIENT)))
                .defaultIfEmpty(unauthorized());
    }

    private TokenResponse tokenFor(String subject, String role) {
        return new TokenResponse(
                jwtUtil.generateToken(subject, Set.of(role)),
                "Bearer",
                jwtProperties.getExpiration().toSeconds());
    }

    private static ResponseEntity<TokenResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    public record TokenRequest(String username, String password) {
    }

    public record ClientTokenRequest(String clientId, String apiKey) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
