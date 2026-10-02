package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Flux;

/**
 * Exercises the gateway against real Redis and Postgres containers instead of mocks. Requires
 * Docker; the whole class is skipped automatically (not failed) when Docker is unavailable, so
 * it can safely live alongside the mock-based tests in environments without a Docker daemon.
 *
 * <p>Verifies two things the mocked tests can't: that the token-bucket Lua script is race-safe
 * under real concurrent access to Redis, and that a client tier created through the admin API is
 * actually persisted to Postgres and enforced end to end (auth -> DB-backed tier resolution ->
 * Redis rate limiting -> routing).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisPostgresIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("rate_limiter")
                    .withUsername("rate_limiter")
                    .withPassword("rate_limiter");

    private static HttpServer backendServer;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        backendServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        backendServer.createContext("/hello", exchange -> {
            byte[] body = "{\"service\":\"fake-backend\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backendServer.start();
        registry.add("DEMO_BACKEND_URL", () -> "http://localhost:" + backendServer.getAddress().getPort());
    }

    @AfterAll
    static void stopBackendServer() {
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    RedisScript<String> tokenBucketScript;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void tokenBucketScriptAllowsExactlyCapacityConcurrentRequests() {
        String key = "test:concurrency:" + UUID.randomUUID();
        int capacity = 5;
        List<String> keys = List.of(key);
        List<String> arguments = List.of(String.valueOf(capacity), String.valueOf(capacity), "1000", "1");

        Long allowedCount = Flux.range(0, 20)
                .flatMap(ignored -> redisTemplate.execute(tokenBucketScript, keys, arguments).next())
                .filter(result -> result.startsWith("1:"))
                .count()
                .block(Duration.ofSeconds(10));

        assertThat(allowedCount).isEqualTo((long) capacity);
    }

    @Test
    void dynamicClientTierFromDatabaseAppliesAcrossTheFullFilterChain() {
        String adminToken = jwtUtil.generateToken("demo");
        String clientId = "tenant-" + UUID.randomUUID();

        client().post().uri("/admin/clients")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .bodyValue(Map.of(
                        "clientId", clientId,
                        "capacity", 3,
                        "refillTokens", 3,
                        "refillPeriodMillis", 1000))
                .exchange()
                .expectStatus().isCreated();

        String clientToken = jwtUtil.generateToken(clientId);

        for (int i = 0; i < 3; i++) {
            client().get().uri("/api/demo/hello")
                    .headers(headers -> headers.setBearerAuth(clientToken))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Limit", "3");
        }

        client().get().uri("/api/demo/hello")
                .headers(headers -> headers.setBearerAuth(clientToken))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Limit", "3");
    }
}
