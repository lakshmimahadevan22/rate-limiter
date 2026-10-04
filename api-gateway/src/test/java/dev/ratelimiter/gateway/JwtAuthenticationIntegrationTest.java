package dev.ratelimiter.gateway;

import static org.springframework.test.web.reactive.server.WebTestClient.bindToServer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=false")
class JwtAuthenticationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @MockitoBean
    ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    ClientCredentialRepository clientRepository;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = bindToServer().baseUrl("http://localhost:" + port).build();
        when(clientRepository.findById(anyString())).thenReturn(Mono.empty());
    }

    private String clientToken(String clientId) {
        return jwtUtil.generateToken(clientId, Set.of(Roles.CLIENT));
    }

    private String adminToken() {
        return jwtUtil.generateToken("demo", Set.of(Roles.ADMIN));
    }

    @Test
    void apiRoutesRejectMissingTokenAndAcceptValidToken() {
        client.get().uri("/api/not-routed")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/not-routed")
                .headers(headers -> headers.setBearerAuth(clientToken("demo")))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void tokenEndpointIssuesTokenForConfiguredDemoUser() {
        client.post().uri("/auth/token")
                .bodyValue(new AuthController.TokenRequest("demo", "demo-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer");
    }

    @Test
    void adminApiRequiresAdminRole() {
        client.get().uri("/admin/clients/acme")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/admin/clients/acme")
                .headers(headers -> headers.setBearerAuth(clientToken("acme")))
                .exchange()
                .expectStatus().isForbidden();

        // Past the security check: the mocked repository has no such client.
        client.get().uri("/admin/clients/acme")
                .headers(headers -> headers.setBearerAuth(adminToken()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void operatorLoginTokenCanUseAdminApi() {
        String token = client.post().uri("/auth/token")
                .bodyValue(new AuthController.TokenRequest("demo", "demo-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthController.TokenResponse.class)
                .returnResult()
                .getResponseBody()
                .accessToken();

        client.get().uri("/admin/clients/acme")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void rejectsRateLimitedRequestBeforeForwardingToBackend() {
        doReturn(Flux.just("0:0:1500:5"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyList());

        client.get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(clientToken("demo")))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectHeader().valueEquals("X-RateLimit-Limit", "5")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("api-gateway:rate-limit:demo")),
                eq(List.of("5", "5", "1000", "1")));
    }
}
