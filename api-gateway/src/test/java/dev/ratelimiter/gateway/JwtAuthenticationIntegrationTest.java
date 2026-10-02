package dev.ratelimiter.gateway;

import static org.springframework.test.web.reactive.server.WebTestClient.bindToServer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import reactor.core.publisher.Flux;

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

    @Test
    void apiRoutesRejectMissingTokenAndAcceptValidToken() {
        WebTestClient client = bindToServer().baseUrl("http://localhost:" + port).build();

        client.get().uri("/api/not-routed")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/not-routed")
                .headers(headers -> headers.setBearerAuth(jwtUtil.generateToken("demo")))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void tokenEndpointIssuesTokenForConfiguredDemoUser() {
        WebTestClient client = bindToServer().baseUrl("http://localhost:" + port).build();

        client.post().uri("/auth/token")
                .bodyValue(new AuthController.TokenRequest("demo", "demo-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer");
    }

    @Test
    void rejectsRateLimitedRequestBeforeForwardingToBackend() {
        doReturn(Flux.just("0:0:1500:5"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyList());
        WebTestClient client = bindToServer().baseUrl("http://localhost:" + port).build();

        client.get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(jwtUtil.generateToken("demo")))
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