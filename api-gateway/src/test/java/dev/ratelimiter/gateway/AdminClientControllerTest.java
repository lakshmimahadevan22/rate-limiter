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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
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
        when(repository.existsById("acme")).thenReturn(Mono.just(false));
        when(repository.save(any(ClientCredential.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var request = new AdminClientController.CreateClientRequest("acme", 25, 25, 1000);

        var response = controller.createClient(request).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.clientId()).isEqualTo("acme");
        assertThat(body.capacity()).isEqualTo(25);
        assertThat(body.refillTokens()).isEqualTo(25);
        assertThat(body.refillPeriodMillis()).isEqualTo(1000);

        var credentialCaptor = org.mockito.ArgumentCaptor.forClass(ClientCredential.class);
        verify(repository).save(credentialCaptor.capture());
        ClientCredential saved = credentialCaptor.getValue();
        assertThat(saved.getApiKeyHash()).isNotEqualTo(body.apiKey());
        assertThat(ApiKeys.matches(body.apiKey(), saved.getApiKeyHash())).isTrue();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.isNew()).isTrue();

        verify(tierResolver).invalidate("acme");
    }

    @Test
    void rejectsCreateRequestWithBlankClientId() {
        var request = new AdminClientController.CreateClientRequest("", 10, 10, 1000);

        StepVerifier.create(controller.createClient(request))
                .verifyErrorSatisfies(error -> assertStatus(error, HttpStatus.BAD_REQUEST));

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCreateRequestWithUnsafeOrOverlongClientId() {
        for (String clientId : new String[] {"has space", "a/b", "x".repeat(101)}) {
            var request = new AdminClientController.CreateClientRequest(clientId, 10, 10, 1000);

            StepVerifier.create(controller.createClient(request))
                    .verifyErrorSatisfies(error -> assertStatus(error, HttpStatus.BAD_REQUEST));
        }

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCreateRequestWithNonPositiveTier() {
        var request = new AdminClientController.CreateClientRequest("acme", 0, 10, 1000);

        StepVerifier.create(controller.createClient(request))
                .verifyErrorSatisfies(error -> assertStatus(error, HttpStatus.BAD_REQUEST));
    }

    @Test
    void createReturnsConflictWhenClientAlreadyExists() {
        when(repository.existsById("acme")).thenReturn(Mono.just(true));
        var request = new AdminClientController.CreateClientRequest("acme", 10, 10, 1000);

        StepVerifier.create(controller.createClient(request))
                .verifyErrorSatisfies(error -> assertStatus(error, HttpStatus.CONFLICT));

        verify(repository, never()).save(any());
    }

    @Test
    void createReturnsConflictWhenConcurrentInsertWinsTheRace() {
        when(repository.existsById("acme")).thenReturn(Mono.just(false));
        when(repository.save(any(ClientCredential.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate key")));
        var request = new AdminClientController.CreateClientRequest("acme", 10, 10, 1000);

        StepVerifier.create(controller.createClient(request))
                .verifyErrorSatisfies(error -> assertStatus(error, HttpStatus.CONFLICT));
    }

    @Test
    void listsAllClients() {
        when(repository.findAll()).thenReturn(Flux.just(
                new ClientCredential("acme", "hash", 10, 10, 1000, true, Instant.now(), Instant.now()),
                new ClientCredential("globex", "hash", 5, 5, 1000, false, Instant.now(), Instant.now())));

        StepVerifier.create(controller.listClients())
                .assertNext(view -> assertThat(view.clientId()).isEqualTo("acme"))
                .assertNext(view -> {
                    assertThat(view.clientId()).isEqualTo("globex");
                    assertThat(view.enabled()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void updateChangesOnlySuppliedFieldsAndInvalidatesCache() {
        ClientCredential existing =
                new ClientCredential("acme", "hash", 10, 10, 1000, false, Instant.now(), Instant.now());
        when(repository.findById("acme")).thenReturn(Mono.just(existing));
        when(repository.save(any(ClientCredential.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var request = new AdminClientController.UpdateClientRequest(50, null, null, true);

        StepVerifier.create(controller.updateClient("acme", request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().capacity()).isEqualTo(50);
                    assertThat(response.getBody().refillTokens()).isEqualTo(10);
                    assertThat(response.getBody().enabled()).isTrue();
                })
                .verifyComplete();

        verify(tierResolver).invalidate("acme");
    }

    @Test
    void updateRejectsNonPositiveTier() {
        var request = new AdminClientController.UpdateClientRequest(null, -1, null, null);

        StepVerifier.create(controller.updateClient("acme", request))
                .verifyErrorSatisfies(error -> assertStatus(error, HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateReturnsNotFoundForUnknownClient() {
        when(repository.findById("missing")).thenReturn(Mono.empty());
        var request = new AdminClientController.UpdateClientRequest(5, null, null, null);

        StepVerifier.create(controller.updateClient("missing", request))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void rotateApiKeyReplacesStoredHash() {
        ClientCredential existing =
                new ClientCredential("acme", ApiKeys.hash("old-key"), 10, 10, 1000, true, Instant.now(), Instant.now());
        when(repository.findById("acme")).thenReturn(Mono.just(existing));
        when(repository.save(any(ClientCredential.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var response = controller.rotateApiKey("acme").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newKey = response.getBody().apiKey();
        assertThat(ApiKeys.matches(newKey, existing.getApiKeyHash())).isTrue();
        assertThat(ApiKeys.matches("old-key", existing.getApiKeyHash())).isFalse();
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

    private static void assertStatus(Throwable error, HttpStatus status) {
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(status);
    }
}
