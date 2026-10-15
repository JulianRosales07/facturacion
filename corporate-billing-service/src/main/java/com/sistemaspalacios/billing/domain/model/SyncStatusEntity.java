package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity to audit executions of scheduled/manual background sync cycles (Sprint 9, HU-56).
 */
@Entity
@Table(name = "estado_sincronizacion")
public class SyncStatusEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "iniciado_en", nullable = false)
    private Instant startedAt;

    @Column(name = "finalizado_en")
    private Instant finishedAt;

    @Column(name = "duracion_ms")
    private Long durationMs;

    @Column(name = "tipo_ejecucion", nullable = false, length = 20)
    private String executionType; // PROGRAMADA | MANUAL

    @Column(name = "lotes_confirmados")
    private int confirmedBatches;

    @Column(name = "facturas_detectadas")
    private int detectedInvoices;

    @Column(name = "pdfs_clasificados")
    private int classifiedPdfs;

    @Column(name = "errores", columnDefinition = "TEXT")
    private String errors;

    /**
     * EN_PROGRESO | EXITOSA | CON_ADVERTENCIAS | FALLIDA
     * <p>
     * CON_ADVERTENCIAS indica que el ciclo completó su trabajo pero algún ítem concreto
     * falló (por ejemplo un lote que no se pudo confirmar). FALLIDA se reserva para cuando
     * una fase entera no pudo ejecutarse.
     */
    @Column(name = "estado", nullable = false, length = 20)
    private String status;

    protected SyncStatusEntity() {
        // JPA
    }

    public SyncStatusEntity(UUID id, String executionType) {
        this.id = id != null ? id : UUID.randomUUID();
        this.startedAt = Instant.now();
        this.executionType = executionType != null ? executionType : "PROGRAMADA";
        this.status = "EN_PROGRESO";
    }

    public void complete(int confirmedBatches, int detectedInvoices, int classifiedPdfs, String errors) {
        this.finishedAt = Instant.now();
        this.durationMs = java.time.Duration.between(this.startedAt, this.finishedAt).toMillis();
        this.confirmedBatches = confirmedBatches;
        this.detectedInvoices = detectedInvoices;
        this.classifiedPdfs = classifiedPdfs;
        this.errors = errors;
        this.status = (errors == null || errors.isBlank()) ? "EXITOSA" : "CON_ADVERTENCIAS";
    }

    public void fail(String errorMessage) {
        this.finishedAt = Instant.now();
        this.durationMs = java.time.Duration.between(this.startedAt, this.finishedAt).toMillis();
        this.errors = errorMessage;
        this.status = "FALLIDA";
    }

    /**
     * Marca el ciclo como FALLIDA conservando lo que sí alcanzó a procesarse.
     * <p>
     * Si una fase se cae a mitad de camino (por ejemplo la carpeta compartida se vuelve
     * inaccesible) el ciclo es un incidente, pero los lotes ya confirmados antes del fallo
     * son trabajo real que debe quedar reflejado en la auditoría.
     */
    public void fail(int confirmedBatches, int detectedInvoices, int classifiedPdfs, String errorMessage) {
        this.confirmedBatches = confirmedBatches;
        this.detectedInvoices = detectedInvoices;
        this.classifiedPdfs = classifiedPdfs;
        fail(errorMessage);
    }

    public UUID getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getExecutionType() { return executionType; }
    public int getConfirmedBatches() { return confirmedBatches; }
    public int getDetectedInvoices() { return detectedInvoices; }
    public int getClassifiedPdfs() { return classifiedPdfs; }
    public String getErrors() { return errors; }
    public String getStatus() { return status; }

    public String getEstado() { return status; }
    public String getTipoDisparador() { return executionType; }
    public Instant getFechaFin() { return finishedAt; }
    public Long getDuracionMs() { return durationMs != null ? durationMs : 0L; }
    public String getDetalleErrores() { return errors; }
}
