package com.sistemaspalacios.billing.infrastructure.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flujo completo con un cliente STOMP real: pago confirmado → COMMIT → evento de dominio →
 * WebSocket en el canal del NIT y en el canal administrativo. Usa sus propios datos y los borra.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres")
class RealtimeWebSocketEndToEndTest {

    @LocalServerPort private int port;
    @Autowired private PaymentService paymentService;
    @Autowired private RepositorioCliente clients;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioPeriodo periods;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<Map<String, Object>> nitEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<Map<String, Object>> adminEvents = new LinkedBlockingQueue<>();
    private WebSocketStompClient stompClient;
    private StompSession session;
    private CorporateClient client;
    private Invoice invoice;

    /** Convierte el cuerpo JSON del frame a String para inspeccionarlo tal como lo recibe un navegador. */
    private static final class JsonTextConverter extends AbstractMessageConverter {
        JsonTextConverter() { super(MimeTypeUtils.APPLICATION_JSON); }
        @Override protected boolean supports(Class<?> clazz) { return String.class == clazz; }
        @Override protected Object convertFromInternal(Message<?> message, Class<?> targetClass, Object hint) {
            return new String((byte[]) message.getPayload(), StandardCharsets.UTF_8);
        }
        @Override protected Object convertToInternal(Object payload, MessageHeaders headers, Object hint) {
            return payload.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String nit = "WS-" + UUID.randomUUID().toString().substring(0, 8);
        client = clients.saveAndFlush(new CorporateClient(UUID.randomUUID(), nit, "CLIENTE TIEMPO REAL", "Calle 1",
                "rt@cliente.co", "3000000000", (short) 1, (short) 1, BillingType.F1, false, "Pasto", null));
        BillingPeriod period = periods.findByCode("2026-09").orElseGet(() -> periods.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), "2026-09", BillingCutType.ADVANCE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), true)));
        Invoice draft = new Invoice(UUID.randomUUID(), client.getId(), period.getId(), "RT-" + nit.substring(3),
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 23), BillingType.F1, InvoiceStatus.CONSOLIDATED);
        draft.setClientNit(nit);
        draft.setClientBusinessName(client.getBusinessName());
        draft.addItem(new InvoiceItem(null, draft.getId(), BillableItemType.CORPORATE_SERVICE, null, "SERVICIO",
                BigDecimal.ONE, Money.of(85_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)));
        draft.recalculateTotals();
        invoice = repositorioFactura.saveAndFlush(draft);

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JsonTextConverter());
        session = stompClient.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        session.subscribe(RealtimeTopics.invoicesOf(nit), handler(nitEvents));
        session.subscribe(RealtimeTopics.ADMIN_INVOICES, handler(adminEvents));
        Thread.sleep(500); // las suscripciones se registran de forma asíncrona en el broker
        nitEvents.clear();
        adminEvents.clear();
    }

    private StompFrameHandler handler(BlockingQueue<Map<String, Object>> sink) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @SuppressWarnings("unchecked")
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                try { sink.add(json.readValue((String) payload, Map.class)); } catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) session.disconnect();
        if (stompClient != null) stompClient.stop();
        jdbc.update("DELETE FROM pago WHERE cliente_corporativo_id = ?", client.getId());
        jdbc.update("DELETE FROM factura WHERE cliente_corporativo_id = ?", client.getId());
        jdbc.update("DELETE FROM cliente_corporativo WHERE id = ?", client.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitEvent(BlockingQueue<Map<String, Object>> queue, String type, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && type.equals(event.get("eventType"))) return event;
        }
        return null;
    }

    @Test
    @DisplayName("Casos 1 y 6: pago aprobado → factura PAGADA → evento en el canal del NIT y en el administrativo")
    @SuppressWarnings("unchecked")
    void approvedPaymentPublishesInvoiceAndPaymentEvents() throws Exception {
        var checkout = paymentService.checkout(new PaymentService.CheckoutCommand(client.getNit(), List.of(invoice.getId()), List.of()));
        paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(client.getNit(), "CUS-RT-1", List.of(), List.of(),
                null, LocalDate.now(), "PSE", "PSE - Bancolombia", null, null, null, null, checkout.paymentId(), "APPROVED"));

        Map<String, Object> event = awaitEvent(nitEvents, RealtimeEvent.INVOICE_STATUS_CHANGED, 5);
        assertNotNull(event, "el portal del NIT recibe el cambio sin recargar");
        assertNotNull(event.get("eventId"));
        assertNotNull(event.get("occurredAt"));
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertEquals(invoice.getId().toString(), payload.get("invoiceId"));
        assertEquals("F1-" + invoice.getInvoiceNumber(), payload.get("invoiceNumber"));
        assertEquals("CONSOLIDADA", payload.get("previousStatus"));
        assertEquals("PAGADA", payload.get("newStatus"));
        assertEquals(checkout.paymentId().toString(), payload.get("paymentId"));
        assertEquals("CUS-RT-1", payload.get("transactionId"));

        assertNotNull(awaitEvent(adminEvents, RealtimeEvent.INVOICE_STATUS_CHANGED, 5), "el panel administrativo también lo recibe");

        Map<String, Object> paymentEvent = awaitEvent(nitEvents, RealtimeEvent.PAYMENT_STATUS_CHANGED, 5);
        assertNotNull(paymentEvent);
        assertEquals("APLICADO", ((Map<String, Object>) paymentEvent.get("payload")).get("newStatus"));

        // La notificación refleja lo persistido: el estado real se consulta por REST/BD.
        assertEquals(InvoiceStatus.PAID, repositorioFactura.findById(invoice.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Rollback: si la transacción no se confirma no se publica ningún evento")
    void rolledBackChangeIsNeverPublished() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Invoice current = repositorioFactura.findById(invoice.getId()).orElseThrow();
            current.setStatus(InvoiceStatus.VOIDED);
            repositorioFactura.save(current);
            status.setRollbackOnly();
        });
        assertNull(awaitEvent(nitEvents, RealtimeEvent.INVOICE_STATUS_CHANGED, 2));
        assertNull(awaitEvent(adminEvents, RealtimeEvent.INVOICE_STATUS_CHANGED, 1));
        assertEquals(InvoiceStatus.CONSOLIDATED, repositorioFactura.findById(invoice.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("El evento se publica después del commit, nunca antes")
    void publishedOnlyAfterCommit() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Invoice current = repositorioFactura.findById(invoice.getId()).orElseThrow();
            current.setStatus(InvoiceStatus.VOIDED);
            repositorioFactura.saveAndFlush(current);
            try {
                assertNull(awaitEvent(nitEvents, RealtimeEvent.INVOICE_STATUS_CHANGED, 1), "aún no hay commit");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Map<String, Object> event = awaitEvent(nitEvents, RealtimeEvent.INVOICE_STATUS_CHANGED, 5);
        assertNotNull(event, "tras el commit llega el aviso");
    }
}
