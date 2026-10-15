package com.sistemaspalacios.billing.infrastructure.ordenes;

import com.sistemaspalacios.billing.service.ordenes.OrderSystemException;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpOrderSystemClientResilienceTest {

    private OrderSystemProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private HttpOrderSystemClient client;

    @BeforeEach
    void setUp() {
        properties = new OrderSystemProperties();
        properties.setBaseUrl("http://order-system.test/api/v1");
        properties.setApiKey("test-api-key");
        properties.setTimeout(Duration.ofMillis(500));
        properties.setMaxRetries(3);

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new HttpOrderSystemClient(properties, restClientBuilder);
    }

    @Test
    @DisplayName("Resiliencia: Reintento exitoso tras fallos transitorios 503")
    void testRetrySuccessAfterTransientErrors() {
        String jsonSuccess = """
                [
                  {
                    "nit": "900123456-1",
                    "businessName": "CLIENTE RESILIENTE S.A.S",
                    "externalOrderCode": "ORD-RES-01",
                    "siteName": "Sede Principal",
                    "monthlyRate": 2500000.00
                  }
                ]
                """;

        // Intento 1: 503 Service Unavailable
        // Intento 2: 500 Internal Server Error
        // Intento 3: 200 OK
        mockServer.expect(ExpectedCount.once(), requestTo("http://order-system.test/api/v1/subscriptions?period=2026-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        mockServer.expect(ExpectedCount.once(), requestTo("http://order-system.test/api/v1/subscriptions?period=2026-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        mockServer.expect(ExpectedCount.once(), requestTo("http://order-system.test/api/v1/subscriptions?period=2026-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jsonSuccess, MediaType.APPLICATION_JSON));

        List<SubscriptionOrderDto> result = client.fetchSubscriptions("2026-09");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("900123456-1", result.get(0).nit());
        mockServer.verify();
    }

    @Test
    @DisplayName("Resiliencia: Lanza OrderSystemException tras agotar todos los reintentos")
    void testFailureAfterExhaustingRetries() {
        // Fallan los 3 intentos permitidos
        mockServer.expect(ExpectedCount.times(3), requestTo("http://order-system.test/api/v1/subscriptions?period=2026-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        OrderSystemException ex = assertThrows(OrderSystemException.class, () -> client.fetchSubscriptions("2026-09"));
        assertTrue(ex.getMessage().contains("after 3 attempts"));
        mockServer.verify();
    }
}
