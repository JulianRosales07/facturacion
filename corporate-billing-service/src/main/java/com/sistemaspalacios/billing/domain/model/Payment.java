package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment aggregate root (Sprint 9, HU-53, HU-54).
 * Represents a payment transaction made by a client covering one or more invoices.
 */
@Entity
@Table(name = "pago")
public class Payment {

    /** Checkout creado para la pasarela; aún no cubre ninguna factura. */
    public static final String STATUS_PENDING = "PENDIENTE";
    /** Pago confirmado (por la pasarela o por registro manual): sus facturas quedan PAGADA. */
    public static final String STATUS_APPLIED = "APLICADO";
    /** La pasarela informó que la transacción no fue aprobada. */
    public static final String STATUS_REJECTED = "RECHAZADO";
    /** Checkout reemplazado por otro posterior sobre las mismas facturas, antes de confirmarse. */
    public static final String STATUS_CANCELLED = "ANULADO";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "cliente_corporativo_id", nullable = false)
    private UUID clientId;

    @Column(name = "fecha_pago", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "medio_pago", nullable = false, length = 50)
    private String paymentMethod;

    @Column(name = "cuenta_banco", nullable = false, length = 50)
    private String bankAccount;

    @Column(name = "valor_bruto", nullable = false, precision = 14, scale = 2)
    private Money grossAmount = Money.ZERO;

    @Column(name = "total_retenciones", nullable = false, precision = 14, scale = 2)
    private Money totalWithholdings = Money.ZERO;

    @Column(name = "valor_redondeo", nullable = false, precision = 14, scale = 2)
    private Money roundingAmount = Money.ZERO;

    @Column(name = "neto_pagado", nullable = false, precision = 14, scale = 2)
    private Money netPaidAmount = Money.ZERO;

    @Column(name = "numero_transaccion", length = 100)
    private String transactionNumber;

    @Column(name = "clave_idempotencia", length = 128)
    private String idempotencyKey;
    @Column(name = "huella_solicitud", length = 64)
    private String requestFingerprint;
    public void identifyRequest(String key, String fingerprint) {
        this.idempotencyKey = key;
        this.requestFingerprint = fingerprint;
    }
    public String getRequestFingerprint() { return requestFingerprint; }

    @Column(name = "observacion", length = 255)
    private String observation;

    @Column(name = "usuario_creacion", length = 100)
    private String createdBy;

    @Column(name = "estado", nullable = false, length = 20)
    private String status = "APLICADO";

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmado_en")
    private Instant confirmedAt;

    /** Modo de retenciones elegido por el cliente; parte inmutable del histórico (CA-15). */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "modo_aplicacion_retenciones", nullable = false, length = 20, updatable = false)
    private RetentionApplicationMode applicationMode = RetentionApplicationMode.CONSOLIDATED;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "payment")
    private List<PaymentInvoice> invoiceCrossings = new ArrayList<>();

    /** Transiciones de estado aún no publicadas: [anterior (null = creado), nuevo]. */
    @jakarta.persistence.Transient
    private final List<String[]> pendingTransitions = new ArrayList<>();

    /**
     * Spring Data publica estos eventos al guardar el pago; se construyen aquí para incluir la
     * transacción y las facturas cruzadas tal como quedaron al guardar.
     */
    @org.springframework.data.domain.DomainEvents
    java.util.Collection<Object> domainEvents() {
        List<UUID> invoiceIds = invoiceCrossings.stream().map(PaymentInvoice::getInvoiceId).toList();
        return pendingTransitions.stream()
                .<Object>map(t -> new com.sistemaspalacios.billing.domain.event.PaymentStatusChanged(
                        id, clientId, transactionNumber, t[0], t[1], invoiceIds))
                .toList();
    }

    @org.springframework.data.domain.AfterDomainEventPublication
    void clearDomainEvents() {
        pendingTransitions.clear();
    }

    private void transitionTo(String next) {
        String previous = this.status;
        this.status = next;
        pendingTransitions.add(new String[]{previous, next});
    }

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "payment")
    private List<PaymentWithholding> appliedWithholdings = new ArrayList<>();

    protected Payment() {
        // JPA
    }

    public Payment(
            UUID id,
            UUID clientId,
            LocalDate paymentDate,
            String paymentMethod,
            String bankAccount,
            Money grossAmount,
            Money totalWithholdings,
            Money roundingAmount,
            Money netPaidAmount,
            String transactionNumber,
            String observation,
            String createdBy
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        this.paymentDate = Objects.requireNonNull(paymentDate, "paymentDate cannot be null");
        this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod cannot be null");
        this.bankAccount = Objects.requireNonNull(bankAccount, "bankAccount cannot be null");
        this.grossAmount = Objects.requireNonNull(grossAmount, "grossAmount cannot be null");
        this.totalWithholdings = totalWithholdings != null ? totalWithholdings : Money.ZERO;
        this.roundingAmount = roundingAmount != null ? roundingAmount : Money.ZERO;
        this.netPaidAmount = Objects.requireNonNull(netPaidAmount, "netPaidAmount cannot be null");
        this.transactionNumber = transactionNumber;
        this.observation = observation;
        this.createdBy = createdBy;
        this.status = null;
        transitionTo(STATUS_APPLIED);
        this.confirmedAt = Instant.now();
    }

    /**
     * Checkout de pasarela: el pago queda PENDIENTE con su liquidación y snapshot de
     * retenciones congelados, sin referencia bancaria hasta que la pasarela confirme.
     */
    public static Payment pendingCheckout(UUID clientId, Money grossAmount, Money totalWithholdings,
            Money roundingAmount, Money netPaidAmount, String createdBy) {
        Payment payment = new Payment(UUID.randomUUID(), clientId, LocalDate.now(), "PASARELA", "PENDIENTE",
                grossAmount, totalWithholdings, roundingAmount, netPaidAmount, null, null, createdBy);
        payment.pendingTransitions.clear();
        payment.status = null;
        payment.transitionTo(STATUS_PENDING);
        payment.confirmedAt = null;
        return payment;
    }

    /**
     * Invariantes contables del recaudo (HU-52):
     * totalFacturas = netoAntesRedondeo + totalRetenciones y netoAPagar = netoAntesRedondeo + ajuste,
     * es decir totalFacturas = netoAPagar + totalRetenciones - ajusteRedondeo.
     */
    public boolean isBalanced() {
        return grossAmount.equals(netPaidAmount.plus(totalWithholdings).minus(roundingAmount));
    }

    /** Se fija al liquidar, antes de guardar; la columna no es actualizable después. */
    public void liquidatedWith(RetentionApplicationMode mode) {
        this.applicationMode = Objects.requireNonNull(mode, "applicationMode cannot be null");
    }

    public RetentionApplicationMode getApplicationMode() { return applicationMode; }

    public boolean isPending() { return STATUS_PENDING.equals(status); }
    public boolean isApplied() { return STATUS_APPLIED.equals(status); }

    public void confirm(String transactionNumber, String paymentMethod, String bankAccount, LocalDate paymentDate) {
        if (!isPending()) {
            throw new IllegalStateException("Solo un pago PENDIENTE puede confirmarse (estado actual: " + status + ")");
        }
        this.transactionNumber = transactionNumber;
        if (paymentMethod != null && !paymentMethod.isBlank()) this.paymentMethod = paymentMethod;
        if (bankAccount != null && !bankAccount.isBlank()) this.bankAccount = bankAccount;
        if (paymentDate != null) this.paymentDate = paymentDate;
        transitionTo(STATUS_APPLIED);
        this.confirmedAt = Instant.now();
        appliedWithholdings.forEach(w -> w.markApplied(confirmedAt));
    }

    public void reject(String transactionNumber, String reason) {
        if (!isPending()) {
            throw new IllegalStateException("Solo un pago PENDIENTE puede rechazarse (estado actual: " + status + ")");
        }
        this.transactionNumber = transactionNumber;
        this.observation = truncate(reason);
        transitionTo(STATUS_REJECTED);
        appliedWithholdings.forEach(PaymentWithholding::markCancelled);
    }

    public void cancel(String reason) {
        if (!isPending()) {
            throw new IllegalStateException("Solo un pago PENDIENTE puede anularse (estado actual: " + status + ")");
        }
        this.observation = truncate(reason);
        transitionTo(STATUS_CANCELLED);
        appliedWithholdings.forEach(PaymentWithholding::markCancelled);
    }

    public void setObservation(String observation) { this.observation = truncate(observation); }

    private static String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }

    public void addInvoiceCrossing(PaymentInvoice crossing) {
        crossing.setPayment(this);
        this.invoiceCrossings.add(crossing);
    }

    public void addWithholding(PaymentWithholding withholding) {
        withholding.setPayment(this);
        this.appliedWithholdings.add(withholding);
        // Registro manual: el pago nace aplicado, igual que sus retenciones.
        if (isApplied()) withholding.markApplied(confirmedAt);
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getBankAccount() { return bankAccount; }
    public Money getGrossAmount() { return grossAmount; }
    public Money getTotalWithholdings() { return totalWithholdings; }
    public Money getRoundingAmount() { return roundingAmount; }
    public Money getNetPaidAmount() { return netPaidAmount; }
    public String getTransactionNumber() { return transactionNumber; }
    public String getObservation() { return observation; }
    public String getCreatedBy() { return createdBy; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public List<PaymentInvoice> getInvoiceCrossings() { return invoiceCrossings; }
    public List<PaymentWithholding> getAppliedWithholdings() { return appliedWithholdings; }
    public List<PaymentWithholding> getWithholdings() { return appliedWithholdings; }
}
