package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Flux;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "resilience4j.circuitbreaker.instances.demoBackend.sliding-window-size=4",
                "resilience4j.circuitbreaker.instances.demoBackend.minimum-number-of-calls=4",
                "resilience4j.circuitbreaker.instances.demoBackend.failure-rate-threshold=50",
                "resilience4j.circuitbreaker.instances.demoBackend.wait-duration-in-open-state=500ms",
                "resilience4j.circuitbreaker.instances.demoBackend.permitted-number-of-calls-in-half-open-state=2",
                "resilience4j.timelimiter.instances.demoBackend.timeout-duration=300ms",
                "spring.flyway.enabled=false"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CircuitBreakerIntegrationTest {

    private static HttpServer backendServer;
    private static volatile Behavior behavior = Behavior.OK;

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    ReactiveStringRedisTemplate redisTemplate;

    private enum Behavior { OK, FAIL, SLOW }

    @DynamicPropertySource
    static void demoBackendUrl(DynamicPropertyRegistry registry) throws IOException {
        backendServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        backendServer.createContext("/hello", CircuitBreakerIntegrationTest::handle);
        backendServer.start();
        registry.add("DEMO_BACKEND_URL", () -> "http://localhost:" + backendServer.getAddress().getPort());
    }

    @AfterAll
    static void stopBackendServer() {
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            Behavior current = behavior;
            if (current == Behavior.SLOW) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = (current == Behavior.FAIL
                    ? "{\"error\":\"boom\"}"
                    : "{\"service\":\"fake-backend\"}").getBytes();
            int status = current == Behavior.FAIL ? 500 : 200;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    @BeforeEach
    void resetState() {
        behavior = Behavior.OK;
        doReturn(Flux.just("1:999:0:999"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyList());
        circuitBreakerRegistry.circuitBreaker("demoBackend").reset();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String bearerToken() {
        return jwtUtil.generateToken("circuit-breaker-test");
    }

    @Test
    void passesThroughWhenBackendIsHealthy() {
        client().get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(bearerToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("fake-backend");
    }

    @Test
    void opensCircuitAfterRepeatedFailuresAndServesFallback() {
        behavior = Behavior.FAIL;

        for (int i = 0; i < 4; i++) {
            client().get().uri("/api/demo/hello")
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .exchange()
                    .expectStatus().isEqualTo(503);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("demoBackend");
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN));

        client().get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(bearerToken()))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo("degraded");
    }

    @Test
    void halfOpensAfterWaitDurationAndClosesOnSuccess() {
        behavior = Behavior.FAIL;
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("demoBackend");

        for (int i = 0; i < 4; i++) {
            client().get().uri("/api/demo/hello")
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .exchange()
                    .expectStatus().isEqualTo(503);
        }
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN));

        behavior = Behavior.OK;
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN));

        client().get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(bearerToken()))
                .exchange()
                .expectStatus().isOk();
        client().get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(bearerToken()))
                .exchange()
                .expectStatus().isOk();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED));
    }

    @Test
    void timeLimiterTripsFallbackOnSlowBackend() {
        behavior = Behavior.SLOW;

        client().get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(bearerToken()))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo("degraded");
    }
}
