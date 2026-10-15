package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Concepto y porcentaje guardados en la configuración autorizada de un cliente. */
@Entity
@Table(name = "configuracion_retencion_cliente_item")
public class ClientRetentionConfigurationItem {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "configuracion_id", nullable = false)
    private ClientRetentionConfiguration configuration;

    @Column(name = "vigencia_concepto_id", nullable = false)
    private UUID conceptId;

    @Column(name = "porcentaje", nullable = false, precision = 7, scale = 4)
    private BigDecimal percentage;

    protected ClientRetentionConfigurationItem() {
        // JPA
    }

    ClientRetentionConfigurationItem(ClientRetentionConfiguration configuration, UUID conceptId, BigDecimal percentage) {
        this.id = UUID.randomUUID();
        this.configuration = Objects.requireNonNull(configuration, "configuration cannot be null");
        this.conceptId = Objects.requireNonNull(conceptId, "conceptId cannot be null");
        this.percentage = Objects.requireNonNull(percentage, "percentage cannot be null");
    }

    void changePercentage(BigDecimal percentage) {
        this.percentage = Objects.requireNonNull(percentage, "percentage cannot be null");
    }

    public UUID getId() { return id; }
    public UUID getConceptId() { return conceptId; }
    public BigDecimal getPercentage() { return percentage; }
}
