package com.sistemaspalacios.billing.infrastructure.ordenes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration properties for HTTP connection to the Order System.
 */
@Configuration
@ConfigurationProperties(prefix = "ordenes.api")
public class OrderSystemProperties {

    /**
     * Base URL for the Order System API (e.g., https://orders.sistemaspalacios.internal/api/v1).
     * Marked as <PENDIENTE: URLs definitivas del equipo de Órdenes>.
     */
    private String baseUrl = "http://localhost:8089/api/v1";

    /**
     * API key / Bearer token for authentication.
     */
    private String apiKey = "dummy-order-api-token";

    /**
     * HTTP connect and read timeout.
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * Maximum retry attempts for transient network failures.
     */
    private int maxRetries = 3;

    /**
     * Authentication type: BEARER or API_KEY (Sprint 8, HU-36).
     */
    private String authType = "BEARER";

    /**
     * Bearer token or client credentials token for Order System.
     */
    private String token = "dev-order-system-token";

    /**
     * Optional URL to renew token if expired.
     */
    private String tokenRenewUrl = "";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getToken() { return token != null && !token.isBlank() ? token : apiKey; }
    public void setToken(String token) { this.token = token; }

    public String getTokenRenewUrl() { return tokenRenewUrl; }
    public void setTokenRenewUrl(String tokenRenewUrl) { this.tokenRenewUrl = tokenRenewUrl; }
}
