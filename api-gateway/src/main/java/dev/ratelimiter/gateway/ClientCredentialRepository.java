package dev.ratelimiter.gateway;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ClientCredentialRepository extends ReactiveCrudRepository<ClientCredential, String> {
}
