package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import io.jsonwebtoken.Claims;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthControllerTest {

    private static final String API_KEY = "correct-api-key";

    private ClientCredentialRepository repository;
    private JwtUtil jwtUtil;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ClientCredentialRepository.class);
        JwtProperties jwtProperties = new JwtProperties();
        jwtUtil = new JwtUtil(jwtProperties);
        controller = new AuthController(new AuthProperties(), jwtProperties, jwtUtil, repository);
    }

    private static ClientCredential client(String clientId, boolean enabled) {
        return new ClientCredential(
                clientId, ApiKeys.hash(API_KEY), 10, 10, 1000, enabled, Instant.now(), Instant.now());
    }

    private Claims claims(ResponseEntity<AuthController.TokenResponse> response) {
        assertThat(response.getBody()).isNotNull();
        return jwtUtil.parseToken(response.getBody().accessToken());
    }

    @Test
    void issuesClientTokenForValidApiKey() {
        when(repository.findById("acme")).thenReturn(Mono.just(client("acme", true)));

        StepVerifier.create(controller.issueClientToken(new AuthController.ClientTokenRequest("acme", API_KEY)))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Claims claims = claims(response);
                    assertThat(claims.getSubject()).isEqualTo("acme");
                    assertThat(claims.get(JwtUtil.ROLES_CLAIM, List.class)).containsExactly(Roles.CLIENT);
                })
                .verifyComplete();
    }

    @Test
    void rejectsWrongApiKey() {
        when(repository.findById("acme")).thenReturn(Mono.just(client("acme", true)));

        StepVerifier.create(controller.issueClientToken(new AuthController.ClientTokenRequest("acme", "wrong-key")))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void rejectsUnknownClient() {
        when(repository.findById("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(controller.issueClientToken(new AuthController.ClientTokenRequest("ghost", API_KEY)))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void rejectsRevokedClientEvenWithCorrectKey() {
        when(repository.findById("acme")).thenReturn(Mono.just(client("acme", false)));

        StepVerifier.create(controller.issueClientToken(new AuthController.ClientTokenRequest("acme", API_KEY)))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void rejectsBlankCredentialsWithoutQueryingDatabase() {
        StepVerifier.create(controller.issueClientToken(new AuthController.ClientTokenRequest("acme", " ")))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();

        verifyNoInteractions(repository);
    }

    @Test
    void operatorLoginIssuesAdminToken() {
        ResponseEntity<AuthController.TokenResponse> response =
                controller.issueToken(new AuthController.TokenRequest("demo", "demo-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claims(response).get(JwtUtil.ROLES_CLAIM, List.class)).containsExactly(Roles.ADMIN);
    }

    @Test
    void operatorLoginRejectsWrongPassword() {
        ResponseEntity<AuthController.TokenResponse> response =
                controller.issueToken(new AuthController.TokenRequest("demo", "wrong"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
