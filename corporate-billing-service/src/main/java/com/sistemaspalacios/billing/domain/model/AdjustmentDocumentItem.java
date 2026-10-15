package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ítem de línea de un documento de ajuste (item_documento_ajuste).
 */
@Entity
@Table(name = "item_documento_ajuste")
public class AdjustmentDocumentItem {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "documento_ajuste_id")
    private UUID documentId;

    @Column(name = "item_facturable_id")
    private UUID invoiceItemId;

    @Column(name = "descripcion", nullable = false, length = 255)
    private String description;

    @Column(name = "cantidad", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    @Column(name = "valor_unitario", nullable = false, precision = 14, scale = 2)
    private Money unitPrice;

    @Column(name = "valor_descuento", nullable = false, precision = 14, scale = 2)
    private Money discount = Money.ZERO;

    @Column(name = "base_gravable", nullable = false, precision = 14, scale = 2)
    private Money taxableBase = Money.ZERO;

    @Column(name = "porcentaje_iva", nullable = false, precision = 5, scale = 2)
    private BigDecimal ivaRate = BigDecimal.ZERO;

    @Column(name = "valor_iva", nullable = false, precision = 14, scale = 2)
    private Money ivaAmount = Money.ZERO;

    @Column(name = "valor_total", nullable = false, precision = 14, scale = 2)
    private Money totalAmount = Money.ZERO;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected AdjustmentDocumentItem() {
        // JPA
    }

    public AdjustmentDocumentItem(
            UUID id,
            UUID documentId,
            UUID invoiceItemId,
            String description,
            BigDecimal quantity,
            Money unitPrice,
            Money discount,
            BigDecimal ivaRate
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.documentId = documentId;
        this.invoiceItemId = invoiceItemId;
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.quantity = quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0 ? quantity : BigDecimal.ONE;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        this.discount = discount != null ? discount : Money.ZERO;
        this.ivaRate = ivaRate != null ? ivaRate : BigDecimal.ZERO;

        recalculate();
    }

    public void recalculate() {
        Money gross = unitPrice.multiply(quantity);
        if (discount.isGreaterThan(gross)) {
            this.discount = gross;
        }
        this.taxableBase = gross.minus(discount);

        if (ivaRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ivaFactor = ivaRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            this.ivaAmount = taxableBase.multiply(ivaFactor);
        } else {
            this.ivaAmount = Money.ZERO;
        }

        this.totalAmount = taxableBase.plus(ivaAmount);
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public UUID getInvoiceItemId() { return invoiceItemId; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public Money getUnitPrice() { return unitPrice; }
    public Money getDiscount() { return discount; }
    public Money getTaxableBase() { return taxableBase; }
    public BigDecimal getIvaRate() { return ivaRate; }
    public Money getIvaAmount() { return ivaAmount; }
    public Money getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
