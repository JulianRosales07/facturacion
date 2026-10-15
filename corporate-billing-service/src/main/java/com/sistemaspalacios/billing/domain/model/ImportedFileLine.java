package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Line item traceability for imported files (archivo_importado_linea).
 * Records raw payload, parsing status and error messages without blocking batch processing.
 */
@Entity
@Table(name = "archivo_importado_linea")
public class ImportedFileLine {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "archivo_importado_id", nullable = false)
    private UUID importedFileId;

    @Column(name = "numero_linea", nullable = false)
    private int lineNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contenido_crudo", columnDefinition = "jsonb", nullable = false)
    private String rawContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_linea", nullable = false)
    private ImportedLineStatus status = ImportedLineStatus.PENDIENTE;

    @Column(name = "mensaje_error", length = 500)
    private String errorMessage;

    @Column(name = "sede_suscripcion_id")
    private UUID siteId;

    @Column(name = "novedad_orden_id")
    private UUID orderUpdateId;

    @Column(name = "procesado_en")
    private Instant processedAt;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected ImportedFileLine() {
        // JPA
    }

    public ImportedFileLine(
            UUID id,
            UUID importedFileId,
            int lineNumber,
            String rawContent
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.importedFileId = Objects.requireNonNull(importedFileId, "importedFileId cannot be null");
        this.lineNumber = lineNumber;
        this.rawContent = Objects.requireNonNull(rawContent, "rawContent cannot be null");
        this.status = ImportedLineStatus.PENDIENTE;
    }

    public void markSuccess(UUID siteId, UUID orderUpdateId) {
        this.status = ImportedLineStatus.PROCESADA;
        this.siteId = siteId;
        this.orderUpdateId = orderUpdateId;
        this.errorMessage = null;
        this.processedAt = Instant.now();
    }

    public void markError(String errorMessage) {
        this.status = ImportedLineStatus.ERROR;
        if (errorMessage != null && errorMessage.length() > 500) {
            this.errorMessage = errorMessage.substring(0, 500);
        } else {
            this.errorMessage = errorMessage;
        }
        this.processedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getImportedFileId() { return importedFileId; }
    public int getLineNumber() { return lineNumber; }
    public String getRawContent() { return rawContent; }
    public ImportedLineStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public UUID getSiteId() { return siteId; }
    public UUID getOrderUpdateId() { return orderUpdateId; }
    public Instant getProcessedAt() { return processedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(ImportedLineStatus status) { this.status = status; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public void setOrderUpdateId(UUID orderUpdateId) { this.orderUpdateId = orderUpdateId; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
