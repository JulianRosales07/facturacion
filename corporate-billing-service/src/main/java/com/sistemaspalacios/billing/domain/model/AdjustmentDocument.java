package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Documento de ajuste contable (Notas Crédito y Débito) ligado a una factura (documento_ajuste).
 */
@Entity
@Table(name = "documento_ajuste")
public class AdjustmentDocument {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "factura_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "tipo", nullable = false)
    private AdjustmentDocumentType type = AdjustmentDocumentType.CREDIT_NOTE;

    @Column(name = "numero_documento", length = 30)
    private String documentNumber;

    @Column(name = "motivo", nullable = false, length = 255)
    private String reason;

    @Column(name = "valor_bruto", nullable = false, precision = 14, scale = 2)
    private Money grossAmount = Money.ZERO;

    @Column(name = "valor_descuento", nullable = false, precision = 14, scale = 2)
    private Money discountAmount = Money.ZERO;

    @Column(name = "valor_subtotal", nullable = false, precision = 14, scale = 2)
    private Money subtotalAmount = Money.ZERO;

    @Column(name = "valor_iva", nullable = false, precision = 14, scale = 2)
    private Money ivaAmount = Money.ZERO;

    @Column(name = "valor_neto", nullable = false, precision = 14, scale = 2)
    private Money netAmount = Money.ZERO;

    @Column(name = "estado", nullable = false)
    private AdjustmentDocumentStatus status = AdjustmentDocumentStatus.APPLIED;

    @Column(name = "creado_por", length = 100)
    private String createdBy;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "documento_ajuste_id")
    private List<AdjustmentDocumentItem> items = new ArrayList<>();

    protected AdjustmentDocument() {
        // JPA
    }

    public AdjustmentDocument(
            UUID id,
            UUID invoiceId,
            AdjustmentDocumentType type,
            String documentNumber,
            String reason
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        this.type = type != null ? type : AdjustmentDocumentType.CREDIT_NOTE;
        this.documentNumber = documentNumber;
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Adjustment document reason cannot be empty");
        }
        this.reason = reason;
        this.status = AdjustmentDocumentStatus.APPLIED;
    }

    public void addItem(AdjustmentDocumentItem item) {
        Objects.requireNonNull(item, "item cannot be null");
        item.setDocumentId(this.id);
        this.items.add(item);
        recalculateTotals();
    }

    public void recalculateTotals() {
        Money runningGross = Money.ZERO;
        Money runningDiscount = Money.ZERO;
        Money runningSubtotal = Money.ZERO;
        Money runningIva = Money.ZERO;

        for (AdjustmentDocumentItem item : items) {
            runningGross = runningGross.plus(item.getUnitPrice().multiply(item.getQuantity()));
            runningDiscount = runningDiscount.plus(item.getDiscount());
            runningSubtotal = runningSubtotal.plus(item.getTaxableBase());
            runningIva = runningIva.plus(item.getIvaAmount());
        }

        this.grossAmount = runningGross;
        this.discountAmount = runningDiscount;
        this.subtotalAmount = runningSubtotal;
        this.ivaAmount = runningIva;
        this.netAmount = runningSubtotal.plus(runningIva);
    }

    public void apply() {
        if (items.isEmpty()) {
            throw new IllegalStateException("Cannot apply an adjustment document without line items");
        }
        this.status = AdjustmentDocumentStatus.APPLIED;
    }

    public void voidDocument(String reason) {
        if (this.status == AdjustmentDocumentStatus.VOIDED) {
            throw new IllegalStateException("Document is already voided");
        }
        this.status = AdjustmentDocumentStatus.VOIDED;
    }

    // Getters and setters
    public UUID getId() { return id; }
    public UUID getInvoiceId() { return invoiceId; }
    public AdjustmentDocumentType getType() { return type; }
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public String getReason() { return reason; }
    public Money getGrossAmount() { return grossAmount; }
    public Money getDiscountAmount() { return discountAmount; }
    public Money getSubtotalAmount() { return subtotalAmount; }
    public Money getIvaAmount() { return ivaAmount; }
    public Money getNetAmount() { return netAmount; }
    public AdjustmentDocumentStatus getStatus() { return status; }
    public void setStatus(AdjustmentDocumentStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public List<AdjustmentDocumentItem> getItems() { return Collections.unmodifiableList(items); }
}
