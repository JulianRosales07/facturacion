package com.sistemaspalacios.billing.infrastructure.ordenes;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.service.ordenes.OrderSystemException;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpOrderSystemClientTest {

    private MockRestServiceServer mockServer;
    private HttpOrderSystemClient client;

    @BeforeEach
    void setUp() {
        OrderSystemProperties properties = new OrderSystemProperties();
        properties.setBaseUrl("http://mock-orders.internal/api/v1");
        properties.setApiKey("test-secret-key");
        properties.setAuthType("API_KEY");
        properties.setTimeout(Duration.ofSeconds(5));

        RestClient.Builder builder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(builder).build();
        this.client = new HttpOrderSystemClient(properties, builder);
    }

    @Test
    @DisplayName("fetchSubscriptions: successfully retrieves and parses subscription records from mock Orders server")
    void testFetchSubscriptionsSuccess() {
        String jsonResponse = """
            [
              {
                "origin": "SPE",
                "nit": "900245045",
                "businessName": "Mega Red Corp",
                "externalOrderCode": "ORD-001",
                "nic": "NIC-101",
                "siteName": "Pasto Centro",
                "address": "Calle 18 # 25-10",
                "monthlyValue": 350000.00,
                "certificationDate": "2026-08-05",
                "brandId": 1,
                "zoneId": 1,
                "billingType": "F1",
                "municipality": "Pasto",
                "appliesWithholding": true,
                "billingEmail": "billing@megared.com",
                "phone": "3001234567",
                "installationTotalAmount": 600000.00,
                "installationTotalInstallments": 3,
                "installationInstallmentAmount": 200000.00
              }
            ]
            """;

        mockServer.expect(requestTo("http://mock-orders.internal/api/v1/subscriptions?period=2026-08"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "test-secret-key"))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<SubscriptionOrderDto> result = client.fetchSubscriptions("2026-08");

        assertNotNull(result);
        assertEquals(1, result.size());
        SubscriptionOrderDto sub = result.get(0);
        assertEquals("900245045", sub.nit());
        assertEquals("ORD-001", sub.externalOrderCode());
        assertEquals(OrderOrigin.SPE, sub.origin());
        assertEquals(new BigDecimal("350000.00"), sub.monthlyValue());
        assertEquals(BillingType.F1, sub.billingType());
        assertEquals(3, sub.installationTotalInstallments());

        mockServer.verify();
    }

    @Test
    @DisplayName("fetchUpdates: successfully retrieves and parses order updates/novelties from mock Orders server")
    void testFetchUpdatesSuccess() {
        String jsonResponse = """
            [
              {
                "externalOrderCode": "ORD-001",
                "origin": "SPE",
                "updateType": "ALTA",
                "periodCode": "2026-08",
                "value": 150000.00,
                "effectiveDate": "2026-08-15",
                "includeInConsolidated": true,
                "observation": "Alta a mitad de mes"
              }
            ]
            """;

        mockServer.expect(requestTo("http://mock-orders.internal/api/v1/updates?period=2026-08"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "test-secret-key"))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<OrderUpdateDto> result = client.fetchUpdates("2026-08");

        assertNotNull(result);
        assertEquals(1, result.size());
        OrderUpdateDto update = result.get(0);
        assertEquals("ORD-001", update.externalOrderCode());
        assertEquals(OrderUpdateType.ALTA, update.updateType());
        assertEquals(new BigDecimal("150000.00"), update.value());
        assertTrue(update.includeInConsolidated());
        assertEquals("Alta a mitad de mes", update.observation());

        mockServer.verify();
    }

    @Test
    @DisplayName("fetchSubscriptions: throws OrderSystemException when server returns 500 error")
    void testFetchSubscriptionsServerError() {
        mockServer.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo("http://mock-orders.internal/api/v1/subscriptions?period=2026-08"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(OrderSystemException.class, () -> client.fetchSubscriptions("2026-08"));
        mockServer.verify();
    }
}
