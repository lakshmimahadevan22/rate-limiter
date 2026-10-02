package dev.ratelimiter.gateway;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class GatewayRequestFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Logger logger = LoggerFactory.getLogger(GatewayRequestFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        String resolvedRequestId = requestId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, resolvedRequestId))
                .build();
        ServerWebExchange filteredExchange = exchange.mutate().request(request).build();
        filteredExchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, resolvedRequestId);

        long startedAt = System.nanoTime();
        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        logger.info("Incoming request method={} path={} requestId={}", method, path, resolvedRequestId);

        return chain.filter(filteredExchange)
                .doOnSuccess(ignored -> logger.info(
                        "Completed request method={} path={} status={} requestId={} durationMs={}",
                        method,
                        path,
                        filteredExchange.getResponse().getStatusCode(),
                        resolvedRequestId,
                        (System.nanoTime() - startedAt) / 1_000_000))
                .doOnError(error -> logger.warn(
                        "Failed request method={} path={} requestId={}",
                        method,
                        path,
                        resolvedRequestId,
                        error));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}