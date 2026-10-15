package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Registro de auditoría de la administración de accesos (tabla auditoria_acceso). Inmutable. */
@Entity
@Table(name = "auditoria_acceso")
public class AccessAuditEntry {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "actor", nullable = false, length = 150, updatable = false)
    private String actor;

    @Column(name = "accion", nullable = false, length = 60, updatable = false)
    private String action;

    @Column(name = "entidad", nullable = false, length = 30, updatable = false)
    private String entity;

    @Column(name = "entidad_id", updatable = false)
    private UUID entityId;

    @Column(name = "detalle", columnDefinition = "TEXT", updatable = false)
    private String detail;

    @Column(name = "ocurrido_en", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AccessAuditEntry() {
        // JPA
    }

    public AccessAuditEntry(String actor, String action, String entity, UUID entityId, String detail) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getEntity() { return entity; }
    public UUID getEntityId() { return entityId; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
