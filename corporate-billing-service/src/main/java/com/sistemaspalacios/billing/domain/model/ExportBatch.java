package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing an export batch to Siigo (lote_exportacion).
 */
@Entity
@Table(name = "lote_exportacion")
public class ExportBatch {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "periodo_facturacion_id", nullable = false)
    private UUID periodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_facturacion", nullable = false)
    private BillingType billingType;

    @Column(name = "tipo_corte", nullable = false, length = 20)
    private String cutType;

    @Column(name = "nombre_archivo", length = 255)
    private String fileName;

    @Column(name = "total_facturas", nullable = false)
    private int totalInvoices;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private ExportBatchStatus status = ExportBatchStatus.GENERADO;

    @Column(name = "generado_en", insertable = false, updatable = false)
    private Instant generatedAt;

    @Column(name = "confirmado_en")
    private Instant confirmedAt;

    @Column(name = "generado_por", length = 100)
    private String generatedBy;

    @Column(name = "confirmado_por", length = 100)
    private String confirmedBy;

    protected ExportBatch() {
        // JPA requirement
    }

    public ExportBatch(
            UUID id,
            UUID periodId,
            BillingType billingType,
            String cutType,
            String fileName,
            int totalInvoices,
            ExportBatchStatus status
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.periodId = Objects.requireNonNull(periodId, "periodId cannot be null");
        this.billingType = Objects.requireNonNull(billingType, "billingType cannot be null");
        this.cutType = Objects.requireNonNull(cutType, "cutType cannot be null");
        this.fileName = fileName;
        this.totalInvoices = totalInvoices;
        this.status = status != null ? status : ExportBatchStatus.GENERADO;
    }

    public UUID getId() { return id; }
    public UUID getPeriodId() { return periodId; }
    public BillingType getBillingType() { return billingType; }
    public String getCutType() { return cutType; }
    public String getFileName() { return fileName; }
    public int getTotalInvoices() { return totalInvoices; }
    public ExportBatchStatus getStatus() { return status; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }

    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setTotalInvoices(int totalInvoices) { this.totalInvoices = totalInvoices; }
    public void confirm() {
        confirm(null);
    }

    public void confirm(String user) {
        this.status = ExportBatchStatus.CONFIRMADO;
        this.confirmedAt = Instant.now();
        if (user != null && !user.isBlank()) {
            this.confirmedBy = user;
        }
    }
}
