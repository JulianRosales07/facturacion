package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad de persistencia para incidencias auditables del ciclo mensual de facturación.
 */
@Entity
@Table(name = "incidencia_facturacion")
public class BillingIncident {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clientId;

    @Column(name = "nit", nullable = false, length = 20)
    private String nit;

    @Column(name = "razon_social", nullable = false, length = 255)
    private String businessName;

    @Column(name = "periodo", nullable = false, length = 10)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_incidencia", nullable = false, length = 50)
    private BillingIncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_atencion", nullable = false, length = 30)
    private BillingIncidentStatus status = BillingIncidentStatus.ABIERTA;

    @Column(name = "descripcion", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "dia_corte_esperado")
    private Integer expectedCutoffDay;

    @Column(name = "fecha_corte_esperada")
    private LocalDate expectedCutoffDate;

    @Column(name = "fecha_deteccion", nullable = false)
    private Instant detectedAt;

    @Column(name = "fecha_resolucion")
    private Instant resolvedAt;

    @Column(name = "observaciones_resolucion", columnDefinition = "TEXT")
    private String resolutionObservations;

    @Column(name = "responsable_asignado", length = 150)
    private String assignedTo;

    @Column(name = "observaciones_seguimiento", columnDefinition = "TEXT")
    private String followUpNotes;

    @Column(name = "notificacion_enviada", nullable = false)
    private boolean notificationSent = false;

    @Column(name = "canal_notificacion", length = 50)
    private String notificationChannel;

    @Column(name = "fecha_notificacion")
    private Instant notificationSentAt;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en")
    private Instant updatedAt;

    protected BillingIncident() {
        // JPA
    }

    public BillingIncident(
            UUID id,
            UUID clientId,
            String nit,
            String businessName,
            String period,
            BillingIncidentType incidentType,
            String description,
            Integer expectedCutoffDay,
            LocalDate expectedCutoffDate,
            Instant detectedAt
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        this.nit = Objects.requireNonNull(nit, "nit cannot be null");
        this.businessName = Objects.requireNonNull(businessName, "businessName cannot be null");
        this.period = Objects.requireNonNull(period, "period cannot be null");
        this.incidentType = Objects.requireNonNull(incidentType, "incidentType cannot be null");
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.expectedCutoffDay = expectedCutoffDay;
        this.expectedCutoffDate = expectedCutoffDate;
        this.detectedAt = detectedAt != null ? detectedAt : Instant.now();
        this.status = BillingIncidentStatus.ABIERTA;
        this.updatedAt = Instant.now();
    }

    public void resolve(String observations, String resolvedBy) {
        this.status = BillingIncidentStatus.RESUELTA;
        this.resolvedAt = Instant.now();
        this.resolutionObservations = observations;
        if (resolvedBy != null && !resolvedBy.isBlank()) {
            this.assignedTo = resolvedBy;
        }
        this.updatedAt = Instant.now();
    }

    public void assign(String responsible, String notes) {
        this.assignedTo = responsible;
        if (notes != null && !notes.isBlank()) {
            this.followUpNotes = notes;
        }
        if (this.status == BillingIncidentStatus.ABIERTA) {
            this.status = BillingIncidentStatus.EN_PROCESO;
        }
        this.updatedAt = Instant.now();
    }

    public void markNotificationSent(boolean success, String channel) {
        this.notificationSent = success;
        this.notificationChannel = channel;
        this.notificationSentAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getNit() { return nit; }
    public String getBusinessName() { return businessName; }
    public String getPeriod() { return period; }
    public BillingIncidentType getIncidentType() { return incidentType; }
    public BillingIncidentStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Integer getExpectedCutoffDay() { return expectedCutoffDay; }
    public LocalDate getExpectedCutoffDate() { return expectedCutoffDate; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionObservations() { return resolutionObservations; }
    public String getAssignedTo() { return assignedTo; }
    public String getFollowUpNotes() { return followUpNotes; }
    public boolean isNotificationSent() { return notificationSent; }
    public String getNotificationChannel() { return notificationChannel; }
    public Instant getNotificationSentAt() { return notificationSentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
