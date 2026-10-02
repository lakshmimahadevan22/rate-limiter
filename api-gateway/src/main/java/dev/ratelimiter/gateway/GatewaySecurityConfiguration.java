package dev.ratelimiter.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfiguration {

    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(
            ServerHttpSecurity http,
            JwtAuthenticationWebFilter jwtAuthenticationWebFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/auth/token").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .pathMatchers("/admin/**").authenticated()
                        .anyExchange().permitAll())
                .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}