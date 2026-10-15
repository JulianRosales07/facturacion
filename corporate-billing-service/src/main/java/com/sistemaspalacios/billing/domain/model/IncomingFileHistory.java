package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="historial_recepcion_archivo")
public class IncomingFileHistory {
    @Id private UUID id;
    @Column(name="procesado_en", nullable=false) private Instant processedAt;
    @Column(name="archivo", nullable=false) private String filename;
    @Column(name="origen", nullable=false) private String source;
    @Column(name="estado", nullable=false) private String status;
    @Column(name="sha256") private String sha256;
    @Column(name="nuevos", nullable=false) private int created;
    @Column(name="repetidos", nullable=false) private int repeated;
    @Column(name="detalle", length=1500) private String detail;
    @Column(name="usuario") private String username;
    protected IncomingFileHistory() {}
    public IncomingFileHistory(String filename, String source, String status, String hash, int created, int repeated, String detail, String username) {
        this.id=UUID.randomUUID(); this.processedAt=Instant.now(); this.filename=filename; this.source=source;
        this.status=status; this.sha256=hash; this.created=created; this.repeated=repeated; this.detail=detail; this.username=username;
    }
    public UUID getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
    public String getFilename() { return filename; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getSha256() { return sha256; }
    public int getCreated() { return created; }
    public int getRepeated() { return repeated; }
    public String getDetail() { return detail; }
    public String getUsername() { return username; }
}
