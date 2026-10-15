package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Line item of a consolidated invoice (item_facturable).
 */
@Entity
@Table(name = "item_facturable")
public class InvoiceItem {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "factura_id")
    private UUID invoiceId;

    @Column(name = "tipo_item", nullable = false)
    private BillableItemType itemType;

    @Column(name = "sede_suscripcion_id")
    private UUID siteId;

    @Column(name = "descripcion", nullable = false, length = 255)
    private String description;

    @Column(name = "cantidad", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    @Column(name = "valor_unitario", nullable = false, precision = 14, scale = 2)
    private Money unitPrice;

    @Column(name = "porcentaje_descuento", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(name = "valor_descuento", nullable = false, precision = 14, scale = 2)
    private Money discountValue;

    @Column(name = "base_gravable", nullable = false, precision = 14, scale = 2)
    private Money taxableBase;

    @Column(name = "gravado", nullable = false)
    private boolean taxed;

    @Column(name = "porcentaje_iva", nullable = false, precision = 5, scale = 2)
    private BigDecimal ivaPercentage;

    @Column(name = "valor_iva", nullable = false, precision = 14, scale = 2)
    private Money ivaValue;

    @Column(name = "valor_total", nullable = false, precision = 14, scale = 2)
    private Money totalValue;

    /**
     * Tarifa mensual completa que originó la línea, antes de prorratear (Sprint 10).
     * <p>
     * {@link #unitPrice} guarda el importe ya prorrateado, por lo que la tarifa de origen
     * no era recuperable: al regenerar la factura tras un cambio de plan a mitad de mes,
     * {@code sede_suscripcion.valor_mensual} ya traía la tarifa nueva y la anterior se
     * perdía. Conservarla aquí permite reconstruir la tarifa vigente al inicio del periodo.
     * <p>
     * Puede ser {@code null} en líneas emitidas antes de la migración V10.
     */
    @Column(name = "valor_mensual_base", precision = 14, scale = 2)
    private Money baseMonthlyRate;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Novedad que abrió este tramo (ALTA, CAMBIO_PLAN, REACTIVACION…). {@code null} para el
     * servicio original del mes, las cuotas de instalación y las líneas anteriores a V24.
     */
    @Column(name = "novedad_orden_id")
    private UUID sourceUpdateId;

    public UUID getSourceUpdateId() { return sourceUpdateId; }

    public void setSourceUpdateId(UUID sourceUpdateId) { this.sourceUpdateId = sourceUpdateId; }

    /** Días que cubre el concepto, ambos inclusive. Null en líneas anteriores a V25. */
    @Column(name = "fecha_desde")
    private java.time.LocalDate coveredFrom;

    @Column(name = "fecha_hasta")
    private java.time.LocalDate coveredTo;

    public java.time.LocalDate getCoveredFrom() { return coveredFrom; }
    public java.time.LocalDate getCoveredTo() { return coveredTo; }

    public void setCoveredPeriod(java.time.LocalDate from, java.time.LocalDate to) {
        this.coveredFrom = from;
        this.coveredTo = to;
    }

    /**
     * Descuento por valor exacto (bajada de plan sobre el cargo fijo). Cuando existe, manda
     * sobre {@link #discountPercentage}, que con dos decimales perdería pesos.
     */
    @jakarta.persistence.Convert(converter = com.sistemaspalacios.billing.domain.vo.NullableMoneyConverter.class)
    @Column(name = "valor_descuento_fijo", precision = 14, scale = 2)
    private Money fixedDiscount;

    public Money getFixedDiscount() { return fixedDiscount; }

    /** Suma un descuento por valor al concepto; nunca deja la base por debajo de cero. */
    public void addFixedDiscount(Money amount) {
        Objects.requireNonNull(amount, "amount cannot be null");
        this.fixedDiscount = (fixedDiscount != null ? fixedDiscount : Money.ZERO).plus(amount);
        recalculate();
    }

    protected InvoiceItem() {
        // JPA
    }

    public InvoiceItem(
            UUID id,
            UUID invoiceId,
            BillableItemType itemType,
            UUID siteId,
            String description,
            BigDecimal quantity,
            Money unitPrice,
            BigDecimal discountPercentage,
            boolean taxed,
            BigDecimal ivaPercentage
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.invoiceId = invoiceId;
        this.itemType = Objects.requireNonNull(itemType, "itemType cannot be null");
        this.siteId = siteId;
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.quantity = quantity != null ? quantity : BigDecimal.ONE;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        this.discountPercentage = discountPercentage != null ? discountPercentage : BigDecimal.ZERO;
        this.taxed = taxed;
        this.ivaPercentage = taxed ? (ivaPercentage != null ? ivaPercentage : BigDecimal.valueOf(19)) : BigDecimal.ZERO;

        recalculate();
    }

    @Column(name = "codigo_producto", length = 80)
    private String productCode;

    public String getProductCode() { return productCode; }

    public void recalculate() {
        Money rawGross = unitPrice.multiply(quantity);
        // Un descuento por valor en cero equivale a no tenerlo: manda el porcentaje (regla 5).
        if (fixedDiscount != null && fixedDiscount.isPositive()) {
            this.discountValue = fixedDiscount.isGreaterThan(rawGross) ? rawGross : fixedDiscount;
        } else {
            this.discountValue = rawGross.percentage(discountPercentage);
        }
        this.taxableBase = rawGross.minus(discountValue);

        if (taxed && ivaPercentage != null && ivaPercentage.compareTo(BigDecimal.ZERO) > 0) {
            this.ivaValue = taxableBase.percentage(ivaPercentage);
        } else {
            this.ivaValue = Money.ZERO;
        }

        this.totalValue = taxableBase.plus(ivaValue);
    }

    /** Conserva los valores oficiales, sin recalcular IVA o cantidades desde tasas redondeadas. */
    public static InvoiceItem fromOfficialAmounts(UUID invoiceId, String code, String description,
            BigDecimal quantity, Money subtotal, Money iva) {
        Money unit = Money.of(subtotal.amount().divide(quantity, 2, java.math.RoundingMode.HALF_UP));
        BigDecimal rate = subtotal.isZero() ? BigDecimal.ZERO
                : iva.amount().multiply(new BigDecimal("100")).divide(subtotal.amount(), 2, java.math.RoundingMode.HALF_UP);
        InvoiceItem item = new InvoiceItem(null, invoiceId, BillableItemType.CORPORATE_SERVICE, null,
                description, quantity, unit, BigDecimal.ZERO, iva.isPositive(), rate);
        item.productCode = code;
        item.taxableBase = subtotal;
        item.discountValue = Money.ZERO;
        item.ivaValue = iva;
        item.totalValue = subtotal.plus(iva);
        return item;
    }

    public UUID getId() { return id; }
    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }
    public BillableItemType getItemType() { return itemType; }
    public UUID getSiteId() { return siteId; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public Money getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscountPercentage() { return discountPercentage; }
    public Money getDiscountValue() { return discountValue; }
    public Money getTaxableBase() { return taxableBase; }
    public boolean isTaxed() { return taxed; }
    public BigDecimal getIvaPercentage() { return ivaPercentage; }
    public Money getIvaValue() { return ivaValue; }
    public Money getTotalValue() { return totalValue; }

    public Money getBaseMonthlyRate() { return baseMonthlyRate; }

    public void setBaseMonthlyRate(Money baseMonthlyRate) { this.baseMonthlyRate = baseMonthlyRate; }

    /** {@code true} si la línea corresponde a una cuota de instalación y no a servicio recurrente. */
    public boolean isInstallationFeeLine() {
        return description != null
                && description.toUpperCase(java.util.Locale.ROOT).startsWith("CUOTA INSTALACION");
    }

    public Instant getCreatedAt() { return createdAt; }
}
