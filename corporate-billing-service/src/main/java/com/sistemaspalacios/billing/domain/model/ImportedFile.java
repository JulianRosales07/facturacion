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
 * Audit and tracking entity for batch-imported files (archivo_importado).
 */
@Entity
@Table(name = "archivo_importado")
public class ImportedFile {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre_archivo", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false)
    private OrderOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private FileStatus status = FileStatus.RECIBIDO;

    @Column(name = "metodo_importacion", length = 50)
    private String importMethod = "EXCEL";

    @Column(name = "total_registros")
    private Integer totalRecords = 0;

    @Column(name = "registros_con_error")
    private Integer errorRecords = 0;

    @Column(name = "recibido_en", nullable = false)
    private Instant receivedAt;

    @Column(name = "procesado_en")
    private Instant processedAt;

    protected ImportedFile() {
        // JPA
    }

    public ImportedFile(
            UUID id,
            String fileName,
            OrderOrigin origin
    ) {
        this(id, fileName, origin, "EXCEL");
    }

    public ImportedFile(
            UUID id,
            String fileName,
            OrderOrigin origin,
            String importMethod
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.fileName = Objects.requireNonNull(fileName, "fileName cannot be null");
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.importMethod = importMethod != null ? importMethod : "EXCEL";
        this.status = FileStatus.RECIBIDO;
        this.totalRecords = 0;
        this.errorRecords = 0;
        this.receivedAt = Instant.now();
    }

    public void markProcessing() {
        this.status = FileStatus.PROCESANDO;
    }

    public void complete(int totalRecords, int errorRecords) {
        this.totalRecords = totalRecords;
        this.errorRecords = errorRecords;
        this.processedAt = Instant.now();
        if (totalRecords > 0 && errorRecords == totalRecords) {
            this.status = FileStatus.ERROR;
        } else {
            this.status = FileStatus.PROCESADO;
        }
    }

    public void markError(String reason) {
        this.status = FileStatus.ERROR;
        this.processedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getFileName() { return fileName; }
    public OrderOrigin getOrigin() { return origin; }
    public String getImportMethod() { return importMethod; }
    public FileStatus getStatus() { return status; }
    public Integer getTotalRecords() { return totalRecords; }
    public Integer getErrorRecords() { return errorRecords; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void setImportMethod(String importMethod) { this.importMethod = importMethod; }
    public void setStatus(FileStatus status) { this.status = status; }
    public void setTotalRecords(Integer totalRecords) { this.totalRecords = totalRecords; }
    public void setErrorRecords(Integer errorRecords) { this.errorRecords = errorRecords; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
