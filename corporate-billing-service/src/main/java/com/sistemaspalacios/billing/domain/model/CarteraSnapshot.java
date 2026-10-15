package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Corte de cartera de Siigo ingerido desde la carpeta compartida (Sprint 13).
 * <p>
 * Cabecera de auditoría de un archivo {@code saldos_de_cartera.xlsx}: cuántas filas traía,
 * cuántas se aplicaron y cuántas quedaron sin factura. El hash permite reconocer un archivo
 * ya ingerido, porque el lector de la carpeta compartida no mueve ni marca lo procesado.
 */
@Entity
@Table(name = "cartera_snapshot")
public class CarteraSnapshot {

    public static final String STATUS_PROCESSING = "PROCESANDO";
    public static final String STATUS_APPLIED = "APLICADO";
    public static final String STATUS_WITH_WARNINGS = "CON_ADVERTENCIAS";
    public static final String STATUS_FAILED = "FALLIDO";
    public static final String STATUS_SKIPPED = "OMITIDO_DUPLICADO";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre_archivo", nullable = false, length = 255)
    private String fileName;

    @Column(name = "ruta_archivo", length = 500)
    private String filePath;

    @Column(name = "hash_sha256", length = 64)
    private String sha256Hash;

    @Column(name = "tamano_bytes")
    private Long sizeBytes;

    /** Fecha de generación del corte, derivada del contenido del archivo. */
    @Column(name = "generado_en", nullable = false)
    private Instant generatedAt;

    @Column(name = "filas_totales", nullable = false)
    private int totalRows;

    @Column(name = "filas_emparejadas", nullable = false)
    private int matchedRows;

    @Column(name = "filas_sin_factura", nullable = false)
    private int unmatchedRows;

    @Column(name = "filas_con_error", nullable = false)
    private int errorRows;

    /** Facturas vigentes del sistema que no aparecieron en el corte. */
    @Column(name = "facturas_sin_corte", nullable = false)
    private int invoicesAbsentFromSnapshot;

    @Column(name = "estado", nullable = false, length = 20)
    private String status = STATUS_PROCESSING;

    @Column(name = "errores", columnDefinition = "TEXT")
    private String errors;

    @Column(name = "ingerido_en", insertable = false, updatable = false)
    private Instant ingestedAt;

    @Column(name = "ingerido_por", length = 100)
    private String ingestedBy;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "cartera_snapshot_id")
    private List<CarteraSnapshotLine> lines = new ArrayList<>();

    protected CarteraSnapshot() {
        // JPA
    }

    public CarteraSnapshot(
            UUID id,
            String fileName,
            String filePath,
            String sha256Hash,
            Long sizeBytes,
            Instant generatedAt,
            String ingestedBy
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.fileName = Objects.requireNonNull(fileName, "fileName cannot be null");
        this.filePath = filePath;
        this.sha256Hash = sha256Hash;
        this.sizeBytes = sizeBytes;
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt cannot be null");
        this.ingestedBy = ingestedBy;
    }

    public void addLine(CarteraSnapshotLine line) {
        Objects.requireNonNull(line, "line cannot be null");
        line.setSnapshotId(this.id);
        this.lines.add(line);
    }

    public void complete(int totalRows, int matchedRows, int unmatchedRows, int errorRows,
                         int invoicesAbsentFromSnapshot, String errors) {
        this.totalRows = totalRows;
        this.matchedRows = matchedRows;
        this.unmatchedRows = unmatchedRows;
        this.errorRows = errorRows;
        this.invoicesAbsentFromSnapshot = invoicesAbsentFromSnapshot;
        this.errors = errors;
        boolean clean = (unmatchedRows == 0 && errorRows == 0 && (errors == null || errors.isBlank()));
        this.status = clean ? STATUS_APPLIED : STATUS_WITH_WARNINGS;
    }

    public void fail(String errorMessage) {
        this.errors = errorMessage;
        this.status = STATUS_FAILED;
    }

    public void skipAsDuplicate() {
        this.status = STATUS_SKIPPED;
    }

    public UUID getId() { return id; }
    public String getFileName() { return fileName; }
    public String getFilePath() { return filePath; }
    public String getSha256Hash() { return sha256Hash; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getGeneratedAt() { return generatedAt; }
    public int getTotalRows() { return totalRows; }
    public int getMatchedRows() { return matchedRows; }
    public int getUnmatchedRows() { return unmatchedRows; }
    public int getErrorRows() { return errorRows; }
    public int getInvoicesAbsentFromSnapshot() { return invoicesAbsentFromSnapshot; }
    public String getStatus() { return status; }
    public String getErrors() { return errors; }
    public Instant getIngestedAt() { return ingestedAt; }
    public String getIngestedBy() { return ingestedBy; }
    public List<CarteraSnapshotLine> getLines() { return Collections.unmodifiableList(lines); }
}
