package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pdf_recibido")
public class ReceivedPdf {

    public static final String STATUS_PENDING = "PENDIENTE";
    public static final String STATUS_ASSOCIATED = "ASOCIADO";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre_original", nullable = false)
    private String originalName;

    @Column(name = "sha256", nullable = false, unique = true, length = 64)
    private String sha256;

    @Column(name = "tamano_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "modificado_en_origen", nullable = false)
    private Instant modifiedAtSource;

    @Column(name = "ruta_local", nullable = false, length = 500)
    private String localPath;

    @Column(name = "estado", nullable = false, length = 20)
    private String status;

    @Column(name = "factura_id")
    private UUID invoiceId;

    @Column(name = "motivo", length = 500)
    private String reason;

    @Column(name = "intentos", nullable = false)
    private int attempts;

    @Column(name = "recibido_en", nullable = false)
    private Instant receivedAt;

    @Column(name = "ultimo_intento_en")
    private Instant lastAttemptAt;

    @Column(name = "asociado_en")
    private Instant associatedAt;

    protected ReceivedPdf() {
        // JPA
    }

    public ReceivedPdf(
            String originalName,
            String sha256,
            long sizeBytes,
            Instant modifiedAtSource,
            String localPath,
            String status,
            UUID invoiceId,
            String reason
    ) {
        this.id = UUID.randomUUID();
        this.originalName = originalName;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.modifiedAtSource = modifiedAtSource;
        this.localPath = localPath;
        this.status = status;
        this.invoiceId = invoiceId;
        this.reason = reason;
        this.attempts = 1;
        this.receivedAt = Instant.now();
        this.lastAttemptAt = this.receivedAt;
        if (STATUS_ASSOCIATED.equals(status)) {
            this.associatedAt = this.receivedAt;
        }
    }

    public void markPending(String reason) {
        this.status = STATUS_PENDING;
        this.reason = reason;
        this.attempts++;
        this.lastAttemptAt = Instant.now();
    }

    public void markAssociated(UUID invoiceId, String newLocalPath) {
        this.status = STATUS_ASSOCIATED;
        this.invoiceId = invoiceId;
        this.localPath = newLocalPath;
        this.reason = null;
        this.associatedAt = Instant.now();
        this.lastAttemptAt = this.associatedAt;
    }

    public UUID getId() { return id; }
    public String getOriginalName() { return originalName; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getModifiedAtSource() { return modifiedAtSource; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getStatus() { return status; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getReason() { return reason; }
    public int getAttempts() { return attempts; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getAssociatedAt() { return associatedAt; }
}
