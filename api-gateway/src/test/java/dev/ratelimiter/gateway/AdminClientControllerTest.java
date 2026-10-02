package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AdminClientControllerTest {

    private ClientCredentialRepository repository;
    private ClientTierResolver tierResolver;
    private AdminClientController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ClientCredentialRepository.class);
        tierResolver = mock(ClientTierResolver.class);
        when(tierResolver.invalidate(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        controller = new AdminClientController(repository, tierResolver);
    }

    @Test
    void createsClientWithHashedApiKeyAndInvalidatesCache() {
        when(repository.save(any(ClientCredential.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var request = new AdminClientController.CreateClientRequest("acme", 25, 25, 1000);

        StepVerifier.create(controller.createClient(request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    var body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.clientId()).isEqualTo("acme");
                    assertThat(body.apiKey()).isNotBlank();
                    assertThat(body.capacity()).isEqualTo(25);
                    assertThat(body.refillTokens()).isEqualTo(25);
                    assertThat(body.refillPeriodMillis()).isEqualTo(1000);
                })
                .verifyComplete();

        var credentialCaptor = org.mockito.ArgumentCaptor.forClass(ClientCredential.class);
        verify(repository).save(credentialCaptor.capture());
        ClientCredential saved = credentialCaptor.getValue();
        assertThat(saved.getApiKeyHash()).isNotBlank();
        assertThat(saved.isEnabled()).isTrue();

        verify(tierResolver).invalidate("acme");
    }

    @Test
    void rejectsCreateRequestWithBlankClientId() {
        var request = new AdminClientController.CreateClientRequest("", 10, 10, 1000);

        StepVerifier.create(controller.createClient(request))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void revokeDisablesExistingClientAndInvalidatesCache() {
        ClientCredential existing =
                new ClientCredential("acme", "hash", 10, 10, 1000, true, Instant.now(), Instant.now());
        when(repository.findById("acme")).thenReturn(Mono.just(existing));
        when(repository.save(any(ClientCredential.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(controller.revokeClient("acme"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();

        assertThat(existing.isEnabled()).isFalse();
        verify(tierResolver).invalidate("acme");
    }

    @Test
    void revokeReturnsNotFoundForUnknownClient() {
        when(repository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(controller.revokeClient("missing"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }
}
