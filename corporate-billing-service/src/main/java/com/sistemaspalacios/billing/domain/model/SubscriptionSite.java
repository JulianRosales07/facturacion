package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
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
 * Site / Subscription belonging to a corporate client (sede_suscripcion).
 */
@Entity
@Table(name = "sede_suscripcion")
public class SubscriptionSite {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "cliente_corporativo_id", nullable = false)
    private UUID clientId;

    @Column(name = "codigo_orden_externo", nullable = false, length = 50)
    private String externalOrderCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false)
    private OrderOrigin origin;

    @Column(name = "nic", length = 30)
    private String nic;

    @Column(name = "nombre_sede", length = 100)
    private String siteName;

    @Column(name = "direccion", length = 255)
    private String address;

    @Column(name = "valor_mensual", nullable = false, precision = 14, scale = 2)
    private Money monthlyValue;

    @Column(name = "fecha_certificacion")
    private LocalDate certificationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private RecordStatus status = RecordStatus.ACTIVO;

    @Column(name = "es_residencial", nullable = false)
    private boolean residential = false;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionSite() {
        // JPA
    }

    public SubscriptionSite(
            UUID id,
            UUID clientId,
            String externalOrderCode,
            OrderOrigin origin,
            String nic,
            String siteName,
            String address,
            Money monthlyValue,
            LocalDate certificationDate,
            boolean active
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        this.externalOrderCode = Objects.requireNonNull(externalOrderCode, "externalOrderCode cannot be null");
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.nic = nic;
        this.siteName = siteName;
        this.address = address;
        this.monthlyValue = monthlyValue != null ? monthlyValue : Money.ZERO;
        this.certificationDate = certificationDate;
        this.status = active ? RecordStatus.ACTIVO : RecordStatus.INACTIVO;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getExternalOrderCode() { return externalOrderCode; }
    public void setExternalOrderCode(String externalOrderCode) { this.externalOrderCode = externalOrderCode; }
    public OrderOrigin getOrigin() { return origin; }
    public String getNic() { return nic; }
    public String getSiteName() { return siteName; }
    public String getAddress() { return address; }
    public Money getMonthlyValue() { return monthlyValue; }
    public void setMonthlyValue(Money monthlyValue) { this.monthlyValue = monthlyValue; }
    public LocalDate getCertificationDate() { return certificationDate; }
    public void setCertificationDate(LocalDate certificationDate) { this.certificationDate = certificationDate; }
    public void setAddress(String address) { this.address = address; }
    public void setSiteName(String siteName) { this.siteName = siteName; }
    public void setNic(String nic) { this.nic = nic; }

    public boolean isActive() { return status == RecordStatus.ACTIVO; }
    public void setActive(boolean active) { this.status = active ? RecordStatus.ACTIVO : RecordStatus.INACTIVO; }
    public RecordStatus getStatus() { return status; }
    public void setStatus(RecordStatus status) { this.status = status; }
    public void deactivate() { this.status = RecordStatus.INACTIVO; }
    public void activate() { this.status = RecordStatus.ACTIVO; }
    public boolean isResidential() { return residential; }
    public void setResidential(boolean residential) { this.residential = residential; }
    public Instant getCreatedAt() { return createdAt; }
}
