package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing an invoice crossing against a payment (Sprint 9, HU-53).
 */
@Entity
@Table(name = "pago_factura")
public class PaymentInvoice {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pago_id", nullable = false)
    private Payment payment;

    @Column(name = "factura_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "valor_aplicado", nullable = false, precision = 14, scale = 2)
    private Money appliedAmount = Money.ZERO;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected PaymentInvoice() {
        // JPA
    }

    public PaymentInvoice(UUID id, UUID invoiceId, Money appliedAmount) {
        this.id = id != null ? id : UUID.randomUUID();
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        this.appliedAmount = Objects.requireNonNull(appliedAmount, "appliedAmount cannot be null");
    }

    public UUID getId() { return id; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
    public UUID getInvoiceId() { return invoiceId; }
    public Money getAppliedAmount() { return appliedAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
