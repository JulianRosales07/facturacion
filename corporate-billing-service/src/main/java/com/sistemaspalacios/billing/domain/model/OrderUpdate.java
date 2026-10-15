package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Novelty / Update received from the Order System (novedad_orden).
 */
@Entity
@Table(name = "novedad_orden")
public class OrderUpdate {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "sede_suscripcion_id", nullable = false)
    private UUID siteId;

    @Column(name = "tipo_novedad_id", nullable = false)
    private OrderUpdateType updateType;

    @Column(name = "periodo_facturacion_id", nullable = false)
    private UUID periodId;

    @Column(name = "valor", precision = 14, scale = 2)
    private Money value;

    @Column(name = "fecha_efectiva")
    private LocalDate effectiveDate;

    @Column(name = "incluir_en_consolidado", nullable = false)
    private boolean includeInConsolidation = true;

    @Column(name = "observacion", length = 255)
    private String observation;

    /**
     * Versión de factura vigente que consumió esta novedad (Sprint 10).
     * NULL mientras la novedad esté pendiente de facturar.
     */
    @Column(name = "factura_id")
    private UUID invoiceId;

    @Column(name = "facturado_en")
    private Instant billedAt;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected OrderUpdate() {
        // JPA
    }

    public OrderUpdate(
            UUID id,
            UUID siteId,
            OrderUpdateType updateType,
            UUID periodId,
            Money value,
            LocalDate effectiveDate,
            boolean includeInConsolidation,
            String observation
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.siteId = Objects.requireNonNull(siteId, "siteId cannot be null");
        this.updateType = Objects.requireNonNull(updateType, "updateType cannot be null");
        this.periodId = Objects.requireNonNull(periodId, "periodId cannot be null");
        this.value = value != null ? value : Money.ZERO;
        this.effectiveDate = effectiveDate;
        this.includeInConsolidation = includeInConsolidation;
        this.observation = observation;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public OrderUpdateType getUpdateType() { return updateType; }
    public UUID getPeriodId() { return periodId; }
    public Money getValue() { return value; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public boolean isIncludeInConsolidation() { return includeInConsolidation; }
    public String getObservation() { return observation; }
    public UUID getInvoiceId() { return invoiceId; }
    public Instant getBilledAt() { return billedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isBilled() {
        return this.invoiceId != null;
    }

    /** Tarifa de la sede justo antes de esta novedad; null si no aplica o no se conoce. */
    @jakarta.persistence.Convert(converter = com.sistemaspalacios.billing.domain.vo.NullableMoneyConverter.class)
    @Column(name = "valor_anterior", precision = 14, scale = 2)
    private Money previousValue;

    public Money getPreviousValue() { return previousValue; }

    /** Se fija una sola vez, al registrar la novedad: reenviarla no la cambia. */
    public void recordPreviousValue(Money value) {
        if (this.previousValue == null && value != null && value.isPositive()) {
            this.previousValue = value;
        }
    }

    /**
     * Actualiza los datos de la misma novedad cuando el sistema de órdenes la reenvía
     * (RN-05). Conserva el id y la factura que la consumió.
     */
    public void refreshDetails(Money value, boolean includeInConsolidation, String observation) {
        this.value = value != null ? value : Money.ZERO;
        this.includeInConsolidation = includeInConsolidation;
        this.observation = observation;
    }

    /**
     * Ata la novedad a la versión de factura que la consumió (Sprint 10).
     * Es idempotente frente a la misma factura: reasignar a la misma versión no falla.
     */
    public void markAsBilled(UUID invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        this.invoiceId = invoiceId;
        this.billedAt = Instant.now();
        record(NoveltyProcessingStatus.APLICADA, "Incorporada a la factura de su periodo");
    }

    /**
     * Libera la novedad cuando la factura que la consumió deja de ser vigente
     * (anulación o reemplazo), de modo que vuelva a estar disponible para la
     * siguiente versión. Un ajuste liberado vuelve a quedar pendiente de ajuste.
     */
    public void releaseFromInvoice() {
        this.invoiceId = null;
        this.billedAt = null;
        if (processingStatus == NoveltyProcessingStatus.AJUSTE_APLICADO) {
            record(NoveltyProcessingStatus.PENDIENTE_AJUSTE, "La factura que tenía el ajuste dejó de estar vigente");
        } else {
            record(NoveltyProcessingStatus.PENDIENTE, "La factura que la tenía dejó de estar vigente");
        }
    }

    // --- Ciclo de vida frente a la facturación (V27) ---------------------------

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "estado_procesamiento", nullable = false, length = 20)
    private NoveltyProcessingStatus processingStatus = NoveltyProcessingStatus.PENDIENTE;

    /** Impacto económico (antes de IVA) cuando se cobra como ajuste: positivo cobra, negativo compensa. */
    @jakarta.persistence.Convert(converter = com.sistemaspalacios.billing.domain.vo.NullableMoneyConverter.class)
    @Column(name = "valor_impacto", precision = 14, scale = 2)
    private Money impactAmount;

    @Column(name = "detalle_procesamiento", length = 255)
    private String processingDetail;

    @Column(name = "procesado_en")
    private Instant processedAt;

    public NoveltyProcessingStatus getProcessingStatus() { return processingStatus; }
    public Money getImpactAmount() { return impactAmount; }
    public String getProcessingDetail() { return processingDetail; }
    public Instant getProcessedAt() { return processedAt; }

    /** Pendiente de facturar en su propio periodo (o liberada). */
    public boolean isAwaitingOwnInvoice() {
        return processingStatus == NoveltyProcessingStatus.PENDIENTE || processingStatus == NoveltyProcessingStatus.APLICADA;
    }

    public boolean isAdjustment() {
        return processingStatus == NoveltyProcessingStatus.PENDIENTE_AJUSTE
                || processingStatus == NoveltyProcessingStatus.AJUSTE_APLICADO;
    }

    /**
     * Su periodo ya estaba emitido o pagado: la factura no se toca y el impacto queda para la
     * siguiente factura del cliente. Un impacto nulo (no calculable) exige revisión manual y
     * no se aplica solo. Es idempotente: si el ajuste ya entró en una factura, no se reinicia.
     */
    public void markPendingAdjustment(Money impact, String detail) {
        if (processingStatus == NoveltyProcessingStatus.AJUSTE_APLICADO) {
            return;
        }
        this.impactAmount = impact;
        record(NoveltyProcessingStatus.PENDIENTE_AJUSTE, detail);
    }

    /** El ajuste entró en la factura {@code invoiceId} (de otro periodo). */
    public void markAdjustmentApplied(UUID invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        this.invoiceId = invoiceId;
        this.billedAt = Instant.now();
        record(NoveltyProcessingStatus.AJUSTE_APLICADO, "Ajuste incorporado en una factura posterior");
    }

    private void record(NoveltyProcessingStatus status, String detail) {
        this.processingStatus = status;
        this.processingDetail = detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail;
        this.processedAt = Instant.now();
    }

    /**
     * Checks if commercial observation explicitly waives proration.
     */
    public boolean waivesProration() {
        if (observation == null) return false;
        String lower = observation.toLowerCase();
        return lower.contains("no cobrar prorrateo") || lower.contains("sin prorrateo") || lower.contains("excluir prorrateo");
    }
}
