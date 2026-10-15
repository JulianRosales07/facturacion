package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Histórico de PDF oficiales asociados a una factura (factura_pdf_historico, Sprint 10).
 * <p>
 * Antes de esta entidad, la ingesta desde la carpeta compartida de Siigo sobrescribía
 * {@code factura.ruta_pdf} sin dejar rastro del archivo anterior. Cada vez que una
 * factura cambia de PDF —por reingesta o por regeneración de versión— el archivo
 * saliente queda registrado aquí con su hash, de modo que el PDF de la factura
 * anterior siga siendo recuperable.
 */
@Entity
@Table(name = "factura_pdf_historico")
public class InvoicePdfHistory {

    public static final String ORIGIN_SHARED_FOLDER = "CARPETA_COMPARTIDA_SIIGO";
    public static final String ORIGIN_REGENERATION = "REGENERACION_VERSION";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "factura_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "numero_version", nullable = false)
    private int versionNumber;

    @Column(name = "ruta_pdf", nullable = false, length = 500)
    private String pdfPath;

    @Column(name = "nombre_archivo", length = 255)
    private String fileName;

    @Column(name = "hash_sha256", length = 64)
    private String sha256Hash;

    @Column(name = "tamano_bytes")
    private Long sizeBytes;

    @Column(name = "numero_factura", length = 30)
    private String invoiceNumber;

    @Column(name = "cufe", length = 96)
    private String cufe;

    @Column(name = "origen", nullable = false, length = 40)
    private String origin = ORIGIN_SHARED_FOLDER;

    @Column(name = "motivo", length = 255)
    private String reason;

    @Column(name = "archivado_en", insertable = false, updatable = false)
    private Instant archivedAt;

    @Column(name = "archivado_por", length = 100)
    private String archivedBy;

    protected InvoicePdfHistory() {
        // JPA
    }

    public InvoicePdfHistory(
            UUID id,
            UUID invoiceId,
            int versionNumber,
            String pdfPath,
            String fileName,
            String sha256Hash,
            Long sizeBytes,
            String invoiceNumber,
            String cufe,
            String origin,
            String reason,
            String archivedBy
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId cannot be null");
        this.versionNumber = versionNumber;
        this.pdfPath = Objects.requireNonNull(pdfPath, "pdfPath cannot be null");
        this.fileName = fileName;
        this.sha256Hash = sha256Hash;
        this.sizeBytes = sizeBytes;
        this.invoiceNumber = invoiceNumber;
        this.cufe = cufe;
        this.origin = (origin != null && !origin.isBlank()) ? origin : ORIGIN_SHARED_FOLDER;
        this.reason = reason;
        this.archivedBy = archivedBy;
    }

    /**
     * Captura el PDF que la factura tiene actualmente asociado, antes de sobrescribirlo.
     *
     * @return {@code null} si la factura no tiene PDF que preservar
     */
    public static InvoicePdfHistory snapshotOf(Invoice invoice, String origin, String reason, String user) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        if (invoice.getPdfPath() == null || invoice.getPdfPath().isBlank()) {
            return null;
        }
        String path = invoice.getPdfPath();
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = separator >= 0 ? path.substring(separator + 1) : path;

        return new InvoicePdfHistory(
                null,
                invoice.getId(),
                invoice.getVersionNumber(),
                path,
                name,
                null,
                null,
                invoice.getInvoiceNumber(),
                invoice.getCufe(),
                origin,
                reason,
                user
        );
    }

    public UUID getId() { return id; }
    public UUID getInvoiceId() { return invoiceId; }
    public int getVersionNumber() { return versionNumber; }
    public String getPdfPath() { return pdfPath; }
    public String getFileName() { return fileName; }
    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getCufe() { return cufe; }
    public String getOrigin() { return origin; }
    public String getReason() { return reason; }
    public Instant getArchivedAt() { return archivedAt; }
    public String getArchivedBy() { return archivedBy; }
}
