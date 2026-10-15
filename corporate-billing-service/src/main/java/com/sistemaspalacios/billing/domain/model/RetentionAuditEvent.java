package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.SelectionAudit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Historial de interacción (retencion_evento): qué hizo la persona durante el proceso. Es distinto
 * del snapshot del pago ({@link PaymentWithholding}, lo que quedó aplicado). Solo inserción.
 */
@Entity
@Table(name = "retencion_evento")
public class RetentionAuditEvent {

    public enum Type { SELECTED, UPDATED, REMOVED, NOT_APPLICABLE, APPLIED, CANCELLED }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "sesion_seleccion", nullable = false, length = 64, updatable = false)
    private String sessionId;

    @Column(name = "pago_id", updatable = false)
    private UUID paymentId;

    @Column(name = "factura_id", updatable = false)
    private UUID invoiceId;

    @Column(name = "nit_cliente", length = 30, updatable = false)
    private String clientNit;

    @Column(name = "vigencia_concepto_id", updatable = false)
    private UUID conceptValidityId;

    @Column(name = "concepto_codigo", nullable = false, length = 20, updatable = false)
    private String conceptCode;

    @Column(name = "concepto_nombre", length = 100, updatable = false)
    private String conceptName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_evento", nullable = false, length = 20, updatable = false)
    private Type type;

    @Column(name = "porcentaje_anterior", precision = 9, scale = 6, updatable = false)
    private BigDecimal oldPercentage;

    @Column(name = "porcentaje_nuevo", precision = 9, scale = 6, updatable = false)
    private BigDecimal newPercentage;

    @Column(name = "ocurrido_en", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal_origen", nullable = false, length = 20, updatable = false)
    private SourceChannel channel;

    @Column(name = "actor_usuario_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_usuario", length = 150, updatable = false)
    private String actorUsername;

    @Column(name = "actor_nombre", length = 200, updatable = false)
    private String actorName;

    @Column(name = "actor_nit", length = 30, updatable = false)
    private String actorNit;

    @Column(name = "ip_origen", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "agente_usuario", length = 500, updatable = false)
    private String userAgent;

    protected RetentionAuditEvent() {
        // JPA
    }

    public RetentionAuditEvent(Type type, String sessionId, UUID paymentId, UUID invoiceId, UUID conceptValidityId,
                               String conceptCode, String conceptName, BigDecimal oldPercentage, BigDecimal newPercentage,
                               Instant occurredAt, SelectionAudit audit) {
        this.id = UUID.randomUUID();
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        this.paymentId = paymentId;
        this.invoiceId = invoiceId;
        this.conceptValidityId = conceptValidityId;
        this.conceptCode = Objects.requireNonNull(conceptCode, "conceptCode cannot be null");
        this.conceptName = conceptName;
        this.oldPercentage = oldPercentage;
        this.newPercentage = newPercentage;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        this.channel = audit.channel();
        this.clientNit = audit.clientNit();
        this.actorUserId = audit.userId();
        this.actorUsername = audit.username();
        this.actorName = audit.userFullName();
        this.actorNit = audit.clientNit();
        this.ipAddress = audit.ipAddress();
        this.userAgent = audit.userAgent();
    }

    public UUID getId() { return id; }
    public String getSessionId() { return sessionId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getClientNit() { return clientNit; }
    public UUID getConceptValidityId() { return conceptValidityId; }
    public String getConceptCode() { return conceptCode; }
    public String getConceptName() { return conceptName; }
    public Type getType() { return type; }
    public BigDecimal getOldPercentage() { return oldPercentage; }
    public BigDecimal getNewPercentage() { return newPercentage; }
    public Instant getOccurredAt() { return occurredAt; }
    public SourceChannel getChannel() { return channel; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public String getActorName() { return actorName; }
    public String getActorNit() { return actorNit; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
}
