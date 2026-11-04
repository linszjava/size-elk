package com.size.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

//        String requestId = UUID.randomUUID().toString();
        String incomingId = exchange.getRequest()
                .getHeaders().getFirst("X-Request-ID");

        String requestId = (incomingId == null || incomingId.isBlank())
                ? UUID.randomUUID().toString()
                : incomingId;
        String path = exchange.getRequest().getURI().getPath();
        long start = System.nanoTime();
        ServerWebExchange forwarded = exchange.mutate().request(
                request -> request.headers(headers -> {
                    headers.set("X-Request-ID", requestId);
                })
        ).build();

        exchange.getResponse().getHeaders().set("X-Request-ID", requestId);
        log.atInfo()
                .addKeyValue("requestId", requestId)
                .addKeyValue("path", path)
                .log("网关日志 Gateway Request Started ");

        return chain.filter(forwarded)
                .doOnError(error -> log.atError()
                        .addKeyValue("requestId", requestId)
                        .setCause(error).log("网关路由失败 "))
                .doFinally(signal -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    log.atInfo()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("path", path)
                            .addKeyValue("status", status == null ?0 : status.value())
                            .addKeyValue("signal", signal.name())
                            .addKeyValue("duration_ms",(System.nanoTime() - start ) / 1000000)
                            .log("网关请求完成 ");
                });
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
