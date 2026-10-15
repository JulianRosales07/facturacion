package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.DateRange;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Billing Period (periodo_facturacion).
 */
@Entity
@Table(name = "periodo_facturacion")
public class BillingPeriod {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "codigo", nullable = false, unique = true, length = 7)
    private String code; // YYYY-MM

    @Column(name = "tipo_corte", nullable = false, length = 20)
    private BillingCutType cutType;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate startDate;

    @Column(name = "fecha_fin", nullable = false)
    private LocalDate endDate;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado = "ABIERTO";

    @Transient
    private boolean open = true;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected BillingPeriod() {
        // JPA
    }

    public BillingPeriod(UUID id, String code, BillingCutType cutType, LocalDate startDate, LocalDate endDate, boolean open) {
        this.id = id != null ? id : UUID.randomUUID();
        this.code = Objects.requireNonNull(code, "code cannot be null");
        this.cutType = Objects.requireNonNull(cutType, "cutType cannot be null");
        this.startDate = Objects.requireNonNull(startDate, "startDate cannot be null");
        this.endDate = Objects.requireNonNull(endDate, "endDate cannot be null");
        this.open = open;
        this.estado = open ? "ABIERTO" : "CERRADO";
    }

    @PostLoad
    private void onLoad() {
        this.open = "ABIERTO".equalsIgnoreCase(this.estado);
    }

    @PrePersist
    @PreUpdate
    private void onSave() {
        this.estado = this.open ? "ABIERTO" : "CERRADO";
    }

    public DateRange toDateRange() {
        return new DateRange(startDate, endDate);
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public BillingCutType getCutType() { return cutType; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public boolean isOpen() { return open; }
    public String getEstado() { return estado; }
    public Instant getCreatedAt() { return createdAt; }

    public void close() {
        this.open = false;
        this.estado = "CERRADO";
    }

    public void reopen() {
        this.open = true;
        this.estado = "ABIERTO";
    }
}
