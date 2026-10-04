package dev.ratelimiter.gateway;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationWebFilter extends AuthenticationWebFilter {

    public JwtAuthenticationWebFilter(JwtUtil jwtUtil) {
        super(authenticationManager(jwtUtil));
        setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers("/api/**", "/admin/**"));
        setServerAuthenticationConverter(exchange -> {
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authorization == null) {
                return Mono.empty();
            }
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Mono.error(new BadCredentialsException("Authorization must use the Bearer scheme"));
            }

            String token = authorization.substring(7).trim();
            if (token.isEmpty()) {
                return Mono.error(new BadCredentialsException("Bearer token must not be blank"));
            }
            return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(token, token));
        });
        setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(
                new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
    }

    private static ReactiveAuthenticationManager authenticationManager(JwtUtil jwtUtil) {
        return authentication -> {
            Object credentials = authentication.getCredentials();
            if (!(credentials instanceof String token)) {
                return Mono.error(new BadCredentialsException("Bearer token is required"));
            }
            return Mono.fromCallable(() -> jwtUtil.parseToken(token))
                    .<Authentication>map(claims -> {
                        String subject = claims.getSubject();
                        if (subject == null || subject.isBlank()) {
                            throw new BadCredentialsException("JWT subject is required");
                        }
                        return UsernamePasswordAuthenticationToken.authenticated(
                                subject,
                                null,
                                authorities(claims));
                    })
                    .onErrorMap(JwtException.class,
                            exception -> new BadCredentialsException("Invalid or expired bearer token", exception));
        };
    }

    private static List<GrantedAuthority> authorities(Claims claims) {
        if (!(claims.get(JwtUtil.ROLES_CLAIM) instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}