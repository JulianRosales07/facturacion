package com.sistemaspalacios.billing.domain.model;

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
 * Dynamic extra field value linked to an invoice (campo_extra_factura).
 */
@Entity
@Table(name = "campo_extra_factura")
public class InvoiceExtraField {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "factura_id", nullable = false)
    private UUID invoiceId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "definicion_campo_extra_id", nullable = false)
    private ExtraFieldDefinition definition;

    @Column(name = "valor", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected InvoiceExtraField() {
        // JPA
    }

    public InvoiceExtraField(UUID id, UUID invoiceId, ExtraFieldDefinition definition, String value) {
        this.id = Objects.requireNonNull(id, "ID must not be null");
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId must not be null");
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
        this.definition.validateValue(value);
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public ExtraFieldDefinition getDefinition() {
        return definition;
    }

    public String getValue() {
        return value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void updateValue(String newValue) {
        this.definition.validateValue(newValue);
        this.value = newValue;
    }
}
