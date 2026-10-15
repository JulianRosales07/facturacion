package com.sistemaspalacios.billing.infrastructure.realtime;

import com.sistemaspalacios.billing.domain.event.AccessControlChanged;
import com.sistemaspalacios.billing.domain.event.InvoiceDocumentUpdated;
import com.sistemaspalacios.billing.domain.event.InvoiceStatusChanged;
import com.sistemaspalacios.billing.domain.event.PaymentStatusChanged;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Traduce los eventos de dominio a avisos WebSocket. Solo actúa DESPUÉS DEL COMMIT: si la
 * transacción hace rollback el evento se descarta y ningún frontend ve un estado no persistido.
 * {@code fallbackExecution} cubre los cambios guardados fuera de una transacción (ya confirmados).
 */
@Component
public class RealtimeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final RepositorioCliente clients;

    public RealtimeEventPublisher(SimpMessagingTemplate messagingTemplate, RepositorioCliente clients) {
        this.messagingTemplate = messagingTemplate;
        this.clients = clients;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInvoiceStatusChanged(InvoiceStatusChanged event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nit", RealtimeTopics.normalizeNit(event.clientNit()));
        payload.put("invoiceId", str(event.invoiceId()));
        payload.put("invoiceNumber", documentLabel(event.billingType(), event.invoiceNumber()));
        payload.put("previousStatus", event.previousStatus() != null ? event.previousStatus().getDbValue() : null);
        payload.put("newStatus", event.newStatus().getDbValue());
        payload.put("paymentId", str(event.paymentId()));
        payload.put("transactionId", event.transactionId());
        publish(event.clientNit(), RealtimeEvent.of(RealtimeEvent.INVOICE_STATUS_CHANGED, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInvoiceDocumentUpdated(InvoiceDocumentUpdated event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nit", RealtimeTopics.normalizeNit(event.clientNit()));
        payload.put("invoiceId", str(event.invoiceId()));
        payload.put("invoiceNumber", documentLabel(event.billingType(), event.invoiceNumber()));
        publish(event.clientNit(), RealtimeEvent.of(RealtimeEvent.INVOICE_PDF_AVAILABLE, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaymentStatusChanged(PaymentStatusChanged event) {
        String nit = event.clientId() == null ? null
                : clients.findById(event.clientId()).map(c -> c.getNit()).orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nit", RealtimeTopics.normalizeNit(nit));
        payload.put("paymentId", str(event.paymentId()));
        payload.put("transactionId", event.transactionId());
        payload.put("previousStatus", event.previousStatus());
        payload.put("newStatus", event.newStatus());
        payload.put("invoiceIds", event.invoiceIds() == null ? List.of() : event.invoiceIds().stream().map(UUID::toString).toList());
        publish(nit, RealtimeEvent.of(RealtimeEvent.PAYMENT_STATUS_CHANGED, payload));
    }

    /**
     * Cambios de usuarios, roles o permisos al canal de seguridad. Solo identificadores: cada
     * frontend conectado vuelve a pedir sus permisos a /api/v1/auth/me.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccessControlChanged(AccessControlChanged event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", str(event.userId()));
        payload.put("roleId", str(event.roleId()));
        payload.put("permissionId", str(event.permissionId()));
        RealtimeEvent realtime = RealtimeEvent.of(event.eventType(), payload);
        try {
            messagingTemplate.convertAndSend(RealtimeTopics.SECURITY, realtime);
            log.info("=== EVENTO WS PUBLICADO === tipo={} eventId={} canal={} payload={}",
                    realtime.eventType(), realtime.eventId(), RealtimeTopics.SECURITY, payload);
        } catch (RuntimeException ex) {
            log.warn("No fue posible publicar el evento WS {} {}: {}", realtime.eventType(), realtime.eventId(), ex.getMessage());
        }
    }

    /** Al canal del NIT (portal de pagos) y al canal administrativo. Un fallo al notificar nunca afecta el cambio ya confirmado. */
    private void publish(String nit, RealtimeEvent event) {
        try {
            if (nit != null && !RealtimeTopics.normalizeNit(nit).isEmpty()) {
                messagingTemplate.convertAndSend(RealtimeTopics.invoicesOf(nit), event);
            }
            messagingTemplate.convertAndSend(RealtimeTopics.ADMIN_INVOICES, event);
            log.info("=== EVENTO WS PUBLICADO === tipo={} eventId={} nit={} payload={}",
                    event.eventType(), event.eventId(), nit, event.payload());
        } catch (RuntimeException ex) {
            log.warn("No fue posible publicar el evento WS {} {}: {}", event.eventType(), event.eventId(), ex.getMessage());
        }
    }

    private static String documentLabel(BillingType type, String number) {
        return (type != null ? type.name() : "F1") + "-" + (number != null ? number.trim() : "");
    }

    private static String str(UUID id) {
        return id != null ? id.toString() : null;
    }
}
