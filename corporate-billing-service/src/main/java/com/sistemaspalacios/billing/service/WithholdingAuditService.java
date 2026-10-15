package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentWithholding;
import com.sistemaspalacios.billing.domain.model.RetentionAuditEvent;
import com.sistemaspalacios.billing.domain.model.SelectionStatus;
import com.sistemaspalacios.billing.domain.vo.SelectionAudit;
import com.sistemaspalacios.billing.repository.RepositorioEventoRetencion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Historial de interacción con las retenciones (retencion_evento). No reemplaza al snapshot del
 * pago: registra qué hizo la persona (seleccionó, cambió, quitó) y qué pasó después (no aplicó,
 * se aplicó, se anuló). Cada sesión de selección se compara con su propio historial, de modo que
 * solo se registran cambios reales y nunca se sobrescribe un valor anterior.
 */
@Service
public class WithholdingAuditService {

    private static final Logger log = LoggerFactory.getLogger(WithholdingAuditService.class);

    /** Concepto diligenciado: por factura (PER_INVOICE) o del pago completo (invoiceId nulo). */
    public record SelectedItem(UUID invoiceId, UUID validityId, String conceptCode, String conceptName, BigDecimal percentage) {}

    private final RepositorioEventoRetencion events;

    public WithholdingAuditService(RepositorioEventoRetencion events) {
        this.events = events;
    }

    /**
     * Simulación: se registra en su propia transacción porque la simulación es de solo lectura.
     * Un fallo de auditoría aquí no impide simular (el snapshot definitivo se valida al confirmar).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSimulation(List<SelectedItem> current, SelectionAudit audit) {
        if (audit.sessionId() == null) return;
        try {
            recordInteraction(current, audit, null);
        } catch (RuntimeException ex) {
            log.warn("No se pudo registrar el historial de retenciones de la sesión {}: {}", audit.sessionId(), ex.getMessage());
        }
    }

    /**
     * Compara la selección actual con la última conocida de la sesión y registra SELECTED, UPDATED
     * o REMOVED por cada diferencia. Devuelve los eventos creados.
     */
    @Transactional
    public List<RetentionAuditEvent> recordInteraction(List<SelectedItem> current, SelectionAudit audit, UUID paymentId) {
        Objects.requireNonNull(audit.sessionId(), "sessionId cannot be null");
        Map<String, SelectedItem> previous = replay(audit.sessionId());
        Map<String, SelectedItem> now = new LinkedHashMap<>();
        for (SelectedItem item : current) now.put(key(item), item);

        Instant at = Instant.now();
        List<RetentionAuditEvent> created = new ArrayList<>();
        for (SelectedItem item : now.values()) {
            SelectedItem before = previous.get(key(item));
            if (before == null) {
                created.add(event(RetentionAuditEvent.Type.SELECTED, item, null, item.percentage(), at, audit, paymentId));
            } else if (before.percentage().compareTo(item.percentage()) != 0) {
                created.add(event(RetentionAuditEvent.Type.UPDATED, item, before.percentage(), item.percentage(), at, audit, paymentId));
            }
        }
        for (Map.Entry<String, SelectedItem> entry : previous.entrySet()) {
            if (!now.containsKey(entry.getKey())) {
                SelectedItem removed = entry.getValue();
                created.add(event(RetentionAuditEvent.Type.REMOVED, removed, removed.percentage(), null, at, audit, paymentId));
            }
        }
        events.saveAll(created);
        if (!created.isEmpty()) {
            log.info("=== HISTORIAL RETENCIONES === sesión={} canal={} actor={} eventos={}", audit.sessionId(), audit.channel(),
                    audit.actorLabel(), created.stream().map(e -> e.getType() + " " + e.getConceptCode() + " "
                            + (e.getNewPercentage() != null ? e.getNewPercentage().stripTrailingZeros().toPlainString() + "%" : "")).toList());
        }
        return created;
    }

    /**
     * Evento del ciclo de vida para cada retención del pago según su estado actual del snapshot:
     * NOT_APPLICABLE al liquidar, APPLIED al confirmar el pago, CANCELLED al anularlo o rechazarlo.
     */
    @Transactional
    public void recordLifecycle(Payment payment, SelectionAudit audit) {
        Instant at = Instant.now();
        List<RetentionAuditEvent> created = new ArrayList<>();
        for (PaymentWithholding w : payment.getAppliedWithholdings()) {
            RetentionAuditEvent.Type type = switch (w.getSelectionStatus()) {
                case NOT_APPLICABLE -> RetentionAuditEvent.Type.NOT_APPLICABLE;
                case APPLIED -> RetentionAuditEvent.Type.APPLIED;
                case CANCELLED -> RetentionAuditEvent.Type.CANCELLED;
                case SELECTED -> null;
            };
            if (type == null) continue;
            String session = w.getSelectionSessionId() != null ? w.getSelectionSessionId() : "PAGO-" + payment.getId();
            created.add(new RetentionAuditEvent(type, session, payment.getId(), w.getInvoiceId(), w.getConceptValidityId(),
                    w.getConceptCode(), w.getConceptName(), null, w.getAppliedPercentage(), at,
                    w.getSelectionStatus() == SelectionStatus.NOT_APPLICABLE ? snapshotActor(w, audit) : audit));
        }
        events.saveAll(created);
    }

    /** El "no aplica" se decide al liquidar: el actor es quien seleccionó. */
    private static SelectionAudit snapshotActor(PaymentWithholding w, SelectionAudit fallback) {
        if (w.getSourceChannel() == null) return fallback;
        return new SelectionAudit(w.getSourceChannel(), w.getSelectedByUserId(), w.getSelectedByUsername(), w.getSelectedByName(),
                w.getSelectedByNit(), w.getSelectionSessionId(), w.getIpAddress(), w.getUserAgent());
    }

    /** Selección vigente de la sesión según su historial (SELECTED/UPDATED fijan, REMOVED quita). */
    private Map<String, SelectedItem> replay(String sessionId) {
        Map<String, SelectedItem> state = new LinkedHashMap<>();
        for (RetentionAuditEvent e : events.findBySessionIdOrderByOccurredAtAsc(sessionId)) {
            SelectedItem item = new SelectedItem(e.getInvoiceId(), e.getConceptValidityId(), e.getConceptCode(), e.getConceptName(),
                    e.getNewPercentage());
            switch (e.getType()) {
                case SELECTED, UPDATED -> state.put(key(item), item);
                case REMOVED -> state.remove(key(item));
                default -> { }
            }
        }
        return state;
    }

    private static RetentionAuditEvent event(RetentionAuditEvent.Type type, SelectedItem item, BigDecimal old, BigDecimal now,
                                             Instant at, SelectionAudit audit, UUID paymentId) {
        return new RetentionAuditEvent(type, audit.sessionId(), paymentId, item.invoiceId(), item.validityId(), item.conceptCode(),
                item.conceptName(), old, now, at, audit);
    }

    private static String key(SelectedItem item) {
        return item.invoiceId() + "|" + item.validityId();
    }
}
