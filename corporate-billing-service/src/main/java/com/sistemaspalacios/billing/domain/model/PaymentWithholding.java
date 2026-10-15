package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import com.sistemaspalacios.billing.domain.vo.SelectionAudit;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing an immutable frozen withholding record applied to a payment (Sprint 9, HU-54).
 * Stores the exact version number, rate, threshold and calculated base.
 */
@Entity
@Table(name = "retencion_pago")
public class PaymentWithholding {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pago_id", nullable = false)
    private Payment payment;

    @Column(name = "vigencia_concepto_id")
    private UUID conceptValidityId;

    @Column(name = "numero_version", nullable = false)
    private int versionNumber = 1;

    @Column(name = "concepto_codigo", nullable = false, length = 20)
    private String conceptCode;

    @Column(name = "nombre_concepto", nullable = false, length = 100)
    private String conceptName;

    /** Porcentaje aplicado en la liquidación. */
    @Column(name = "porcentaje", nullable = false, precision = 9, scale = 6, updatable = false)
    private BigDecimal percentage;

    @Column(name = "base_minima", nullable = false, precision = 14, scale = 2, updatable = false)
    private Money minimumBase;

    @Column(name = "base_calculo", nullable = false, precision = 14, scale = 2, updatable = false)
    private Money calculationBase;

    @Column(name = "valor_retenido", nullable = false, precision = 14, scale = 2, updatable = false)
    private Money withheldAmount;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected PaymentWithholding() {
        // JPA
    }

    public PaymentWithholding(
            UUID id,
            UUID conceptValidityId,
            int versionNumber,
            String conceptCode,
            String conceptName,
            BigDecimal percentage,
            Money minimumBase,
            Money calculationBase,
            Money withheldAmount
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.conceptValidityId = conceptValidityId;
        this.versionNumber = Math.max(1, versionNumber);
        this.conceptCode = Objects.requireNonNull(conceptCode, "conceptCode cannot be null");
        this.conceptName = Objects.requireNonNull(conceptName, "conceptName cannot be null");
        this.percentage = Objects.requireNonNull(percentage, "percentage cannot be null");
        this.minimumBase = Objects.requireNonNull(minimumBase, "minimumBase cannot be null");
        this.calculationBase = Objects.requireNonNull(calculationBase, "calculationBase cannot be null");
        this.withheldAmount = Objects.requireNonNull(withheldAmount, "withheldAmount cannot be null");
        this.enteredPercentage = percentage;
        this.selectedAt = Instant.now();
        this.selectionStatus = SelectionStatus.SELECTED;
    }

    /** Ya está en la base de datos: a partir de aquí la evidencia no se modifica. */
    @jakarta.persistence.Transient
    private boolean persisted;

    @jakarta.persistence.PostPersist
    @jakarta.persistence.PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    @Column(name = "motivo_ajuste", length = 255, updatable = false)
    private String adjustmentReason;

    // Snapshot (HU-54): se guarda también el concepto seleccionado que no aplicó, para poder
    // auditar que el cliente lo eligió y por qué no se descontó.
    @Column(name = "aplica", nullable = false)
    private boolean applicable = true;

    @Column(name = "motivo_no_aplica", length = 255)
    private String nonApplicableReason;

    @Column(name = "vigente_desde")
    private LocalDate validFrom;

    @Column(name = "vigente_hasta")
    private LocalDate validTo;

    /** Factura sobre la que se calculó (PER_INVOICE); NULL si se calculó sobre el pago consolidado. */
    @Column(name = "factura_id", updatable = false)
    private UUID invoiceId;

    /** Máximo permitido por el catálogo al liquidar; {@code percentage} es el que ingresó el cliente. */
    @Column(name = "porcentaje_maximo", precision = 7, scale = 4, updatable = false)
    private BigDecimal maxPercentage;

    // --- Auditoría: quién, cuándo y desde dónde (V22). Inmutable salvo SELECTED → APPLIED/CANCELLED. ---

    /** Porcentaje que escribió la persona; se conserva aparte del aplicado para auditoría. */
    @Column(name = "porcentaje_ingresado", nullable = false, precision = 9, scale = 6, updatable = false)
    private BigDecimal enteredPercentage;

    /** Hora del servidor en que se confirmó la selección (checkout o registro). */
    @Column(name = "seleccionado_en", nullable = false, updatable = false)
    private Instant selectedAt;

    /** Hora del servidor en que el pago se confirmó y la retención quedó aplicada. */
    @Column(name = "aplicado_en")
    private Instant appliedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_seleccion", nullable = false, length = 20)
    private SelectionStatus selectionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal_origen", length = 20, updatable = false)
    private SourceChannel sourceChannel;

    @Column(name = "seleccionado_por_usuario_id", updatable = false)
    private UUID selectedByUserId;

    @Column(name = "seleccionado_por_usuario", length = 150, updatable = false)
    private String selectedByUsername;

    @Column(name = "seleccionado_por_nombre", length = 200, updatable = false)
    private String selectedByName;

    @Column(name = "seleccionado_por_nit", length = 30, updatable = false)
    private String selectedByNit;

    @Column(name = "sesion_seleccion", length = 64, updatable = false)
    private String selectionSessionId;

    @Column(name = "ip_origen", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "agente_usuario", length = 500, updatable = false)
    private String userAgent;

    /**
     * Congela una evaluación del catálogo tal como se liquidó. Nunca se reconstruye desde el
     * catálogo vigente: si mañana cambia el porcentaje, este registro conserva el aplicado.
     */
    public static PaymentWithholding snapshotOf(
            com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation evaluation,
            UUID invoiceId,
            BigDecimal maxPercentage,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        PaymentWithholding snapshot = new PaymentWithholding(
                UUID.randomUUID(),
                evaluation.validityId(),
                evaluation.versionNumber(),
                evaluation.conceptCode().name(),
                evaluation.conceptName(),
                evaluation.percentage(),
                evaluation.minimumBase(),
                evaluation.calculationBase(),
                evaluation.withheldAmount()
        );
        snapshot.applicable = evaluation.applicable();
        snapshot.nonApplicableReason = evaluation.nonApplicableReason();
        snapshot.validFrom = validFrom;
        snapshot.validTo = validTo;
        snapshot.invoiceId = invoiceId;
        snapshot.maxPercentage = maxPercentage;
        snapshot.enteredPercentage = evaluation.percentage();
        snapshot.selectedAt = Instant.now();
        snapshot.selectionStatus = evaluation.applicable() ? SelectionStatus.SELECTED : SelectionStatus.NOT_APPLICABLE;
        return snapshot;
    }

    /**
     * Registra quién confirmó la selección, desde dónde y el porcentaje que escribió. Solo antes de
     * guardarse: después la evidencia no cambia. La hora la pone el servidor.
     */
    public void recordSelection(BigDecimal enteredPercentage, Instant selectedAt, SelectionAudit audit) {
        if (persisted) {
            throw new IllegalStateException("La selección de la retención " + id + " ya fue registrada y es inmutable");
        }
        this.enteredPercentage = Objects.requireNonNull(enteredPercentage, "enteredPercentage cannot be null");
        this.selectedAt = Objects.requireNonNull(selectedAt, "selectedAt cannot be null");
        this.sourceChannel = audit.channel();
        this.selectedByUserId = audit.userId();
        this.selectedByUsername = audit.username();
        this.selectedByName = audit.userFullName();
        this.selectedByNit = audit.clientNit();
        this.selectionSessionId = audit.sessionId();
        this.ipAddress = audit.ipAddress();
        this.userAgent = audit.userAgent();
    }

    /** El pago se confirmó: la retención seleccionada queda aplicada (las no aplicables siguen igual). */
    public void markApplied(Instant at) {
        if (selectionStatus == SelectionStatus.SELECTED) {
            selectionStatus = SelectionStatus.APPLIED;
            appliedAt = Objects.requireNonNull(at, "at cannot be null");
        } else if (selectionStatus != SelectionStatus.NOT_APPLICABLE) {
            throw new IllegalStateException("La retención " + id + " está " + selectionStatus + " y no puede aplicarse");
        }
    }

    /** El pago se anuló o rechazó antes de confirmarse. */
    public void markCancelled() {
        if (selectionStatus == SelectionStatus.SELECTED) {
            selectionStatus = SelectionStatus.CANCELLED;
        } else if (selectionStatus == SelectionStatus.APPLIED) {
            throw new IllegalStateException("La retención " + id + " ya fue aplicada; se corrige con un ajuste o reversión");
        }
    }

    public BigDecimal getEnteredPercentage() { return enteredPercentage; }
    /** Porcentaje aplicado (hoy igual al ingresado: el sistema no lo transforma, solo lo valida). */
    public BigDecimal getAppliedPercentage() { return percentage; }
    public Instant getSelectedAt() { return selectedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public SelectionStatus getSelectionStatus() { return selectionStatus; }
    public SourceChannel getSourceChannel() { return sourceChannel; }
    public UUID getSelectedByUserId() { return selectedByUserId; }
    public String getSelectedByUsername() { return selectedByUsername; }
    public String getSelectedByName() { return selectedByName; }
    public String getSelectedByNit() { return selectedByNit; }
    public String getSelectionSessionId() { return selectionSessionId; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }

    public UUID getInvoiceId() { return invoiceId; }
    public BigDecimal getMaxPercentage() { return maxPercentage; }
    public boolean isApplicable() { return applicable; }
    public String getNonApplicableReason() { return nonApplicableReason; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }

    public String getAdjustmentReason() { return adjustmentReason; }
    public void setAdjustmentReason(String reason) {
        if (persisted) {
            throw new IllegalStateException("La retención " + id + " ya fue registrada y es inmutable");
        }
        this.adjustmentReason = reason;
    }

    public UUID getId() { return id; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
    public UUID getConceptValidityId() { return conceptValidityId; }
    public int getVersionNumber() { return versionNumber; }
    public String getConceptCode() { return conceptCode; }
    public String getConceptName() { return conceptName; }
    public BigDecimal getPercentage() { return percentage; }
    public Money getMinimumBase() { return minimumBase; }
    public Money getCalculationBase() { return calculationBase; }
    public Money getWithheldAmount() { return withheldAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
