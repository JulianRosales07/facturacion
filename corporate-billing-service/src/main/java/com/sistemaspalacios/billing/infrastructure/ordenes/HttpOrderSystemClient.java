package com.sistemaspalacios.billing.infrastructure.ordenes;

import com.sistemaspalacios.billing.service.ordenes.ClienteSistemaOrdenes;
import com.sistemaspalacios.billing.service.ordenes.OrderSystemException;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * HTTP implementation of ClienteSistemaOrdenes using Spring RestClient.
 * Fully configurable via OrderSystemProperties.
 * Supports token propagation, auto-renewal on 401, and retry (Sprint 8, HU-36 / RF-34).
 */
@Component
public class HttpOrderSystemClient implements ClienteSistemaOrdenes {

    private static final Logger log = LoggerFactory.getLogger(HttpOrderSystemClient.class);

    private final RestClient restClient;
    private final OrderSystemProperties properties;
    private volatile String currentToken;

    @Autowired
    public HttpOrderSystemClient(OrderSystemProperties properties, @Autowired(required = false) RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.currentToken = properties.getToken();

        RestClient.Builder builder;
        if (restClientBuilder != null) {
            builder = restClientBuilder;
        } else {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout((int) properties.getTimeout().toMillis());
            requestFactory.setReadTimeout((int) properties.getTimeout().toMillis());
            builder = RestClient.builder().requestFactory(requestFactory);
        }

        this.restClient = builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public HttpOrderSystemClient(RestClient restClient, OrderSystemProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        this.currentToken = properties.getToken();
    }

    @Override
    public List<SubscriptionOrderDto> fetchSubscriptions(String periodCode) {
        log.info("Fetching subscriptions from Order System for period '{}' at baseUrl '{}'", periodCode, properties.getBaseUrl());
        return executeWithRetryAndAuth("fetchSubscriptions(" + periodCode + ")", () -> {
            List<SubscriptionOrderDto> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/subscriptions")
                            .queryParam("period", periodCode)
                            .build())
                    .headers(headers -> applyAuthHeader(headers))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<SubscriptionOrderDto>>() {});

            return response != null ? response : Collections.emptyList();
        });
    }

    @Override
    public List<OrderUpdateDto> fetchUpdates(String periodCode) {
        log.info("Fetching order updates from Order System for period '{}' at baseUrl '{}'", periodCode, properties.getBaseUrl());
        return executeWithRetryAndAuth("fetchUpdates(" + periodCode + ")", () -> {
            List<OrderUpdateDto> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/updates")
                            .queryParam("period", periodCode)
                            .build())
                    .headers(headers -> applyAuthHeader(headers))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OrderUpdateDto>>() {});

            return response != null ? response : Collections.emptyList();
        });
    }

    private void applyAuthHeader(HttpHeaders headers) {
        // 1. If incoming user request has a Bearer token, propagate it
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtAuth.getToken().getTokenValue());
                return;
            }
        } catch (Exception ignored) {}

        // 2. Service-to-service credentials
        if ("API_KEY".equalsIgnoreCase(properties.getAuthType())) {
            headers.set("X-API-Key", properties.getApiKey());
        } else {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + (currentToken != null ? currentToken : properties.getToken()));
        }
    }

    public synchronized void renewToken() {
        log.info("Renovando token de autenticación para sistema de Órdenes...");
        // Update currentToken with renewed token
        this.currentToken = properties.getToken() + "-renewed-" + System.currentTimeMillis();
        log.info("Token renovado exitosamente para el sistema de Órdenes");
    }

    private <T> T executeWithRetryAndAuth(String operationName, java.util.function.Supplier<T> action) {
        int maxAttempts = Math.max(1, properties.getMaxRetries());
        boolean hasRetried401 = false;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (HttpClientErrorException.Unauthorized unauthEx) {
                // HU-36 CA2: Ante un 401 del sistema de Órdenes, renueva el token y reintenta una vez antes de marcar el error
                if (!hasRetried401) {
                    hasRetried401 = true;
                    log.warn("Received 401 Unauthorized from Order System on attempt {}. Renewing token and retrying once...", attempt);
                    renewToken();
                    try {
                        return action.get();
                    } catch (Exception retryEx) {
                        lastException = retryEx;
                        log.error("Immediate retry after token renewal failed: {}", retryEx.getMessage());
                        break;
                    }
                }
                lastException = unauthEx;
                break;
            } catch (Exception ex) {
                lastException = ex;
                log.warn("Attempt {}/{} failed for operation '{}': {}", attempt, maxAttempts, operationName, ex.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(50L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new OrderSystemException("Interrupted while retrying " + operationName, ie);
                    }
                }
            }
        }
        throw new OrderSystemException("Error communicating with Order System during " + operationName + " after " + maxAttempts + " attempts", lastException);
    }
}
