package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing a dated legal validity of a tax concept (vigencia_concepto).
 */
@Entity
@Table(name = "vigencia_concepto")
public class TaxConceptValidity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "concepto_tributario_id", nullable = false)
    private TaxConceptCode conceptCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "clasificacion")
    private ServiceClassification classification; // null = applies to all

    @Column(name = "municipio", length = 60)
    private String municipality; // null = applies to all, or specific for ReteICA

    @Column(name = "base_minima", nullable = false, precision = 14, scale = 2)
    private Money minimumBase;

    /**
     * Porcentaje máximo que el cliente puede practicar con este concepto (V20). La tarifa
     * aplicada la ingresa el cliente en cada pago y queda en el snapshot del pago.
     */
    @Column(name = "porcentaje_maximo", nullable = false, precision = 7, scale = 4)
    private BigDecimal maxPercentage;

    @Column(name = "vigente_desde", nullable = false)
    private LocalDate validFrom;

    @Column(name = "vigente_hasta")
    private LocalDate validTo;

    @Column(name = "nombre", length = 100)
    private String name;

    @Column(name = "numero_version", nullable = false)
    private int versionNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private RecordStatus status = RecordStatus.ACTIVO;

    @Column(name = "creado_por", length = 100)
    private String createdBy;

    @Column(name = "actualizado_por", length = 100)
    private String updatedBy;

    protected TaxConceptValidity() {
        // JPA
    }

    public TaxConceptValidity(
            UUID id,
            TaxConceptCode conceptCode,
            String name,
            int versionNumber,
            ServiceClassification classification,
            String municipality,
            Money minimumBase,
            BigDecimal maxPercentage,
            LocalDate validFrom,
            LocalDate validTo,
            boolean active
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.conceptCode = Objects.requireNonNull(conceptCode, "conceptCode cannot be null");
        this.name = (name != null && !name.isBlank()) ? name : conceptCode.getDescription();
        this.versionNumber = Math.max(1, versionNumber);
        this.classification = classification;
        this.municipality = municipality;
        this.minimumBase = Objects.requireNonNull(minimumBase, "minimumBase cannot be null");
        this.maxPercentage = Objects.requireNonNull(maxPercentage, "maxPercentage cannot be null");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom cannot be null");
        this.validTo = validTo;
        this.status = active ? RecordStatus.ACTIVO : RecordStatus.INACTIVO;
    }

    public TaxConceptValidity(
            UUID id,
            TaxConceptCode conceptCode,
            ServiceClassification classification,
            String municipality,
            Money minimumBase,
            BigDecimal maxPercentage,
            LocalDate validFrom,
            LocalDate validTo,
            boolean active
    ) {
        this(id, conceptCode, conceptCode != null ? conceptCode.getDescription() : null, 1, classification, municipality, minimumBase, maxPercentage, validFrom, validTo, active);
    }

    public TaxConceptValidity(
            UUID id,
            String name,
            int versionNumber,
            TaxConceptCode conceptCode,
            ServiceClassification classification,
            String municipality,
            Money minimumBase,
            BigDecimal maxPercentage,
            LocalDate validFrom,
            LocalDate validTo,
            boolean active
    ) {
        this(id, conceptCode, name, versionNumber, classification, municipality, minimumBase, maxPercentage, validFrom, validTo, active);
    }

    public boolean isApplicable(LocalDate date, ServiceClassification targetClassification, String targetMunicipality) {
        if (!isActive()) return false;
        if (date.isBefore(validFrom)) return false;
        if (validTo != null && date.isAfter(validTo)) return false;

        // Check classification filter
        if (this.classification != null && targetClassification != null && this.classification != targetClassification) {
            return false;
        }

        // Check municipality filter for territorial concepts (ReteICA)
        if (this.municipality != null && targetMunicipality != null && !this.municipality.equalsIgnoreCase(targetMunicipality)) {
            return false;
        }

        return true;
    }

    public boolean isActive() {
        return status == RecordStatus.ACTIVO;
    }

    // Getters and record-style accessors for backward compatibility
    public UUID getId() { return id; }
    public UUID id() { return id; }

    public TaxConceptCode getConceptCode() { return conceptCode; }
    public TaxConceptCode conceptCode() { return conceptCode; }

    public ServiceClassification getClassification() { return classification; }
    public ServiceClassification classification() { return classification; }

    public String getMunicipality() { return municipality; }
    public String municipality() { return municipality; }

    public Money getMinimumBase() { return minimumBase; }
    public Money minimumBase() { return minimumBase; }

    @com.fasterxml.jackson.annotation.JsonProperty("porcentajeMaximo")
    public BigDecimal getMaxPercentage() { return maxPercentage; }
    public BigDecimal maxPercentage() { return maxPercentage; }

    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate validFrom() { return validFrom; }

    public LocalDate getValidTo() { return validTo; }
    public LocalDate validTo() { return validTo; }

    // Mismos nombres que en TaxConceptValidityRequest, para que el panel lea lo que escribe.
    @com.fasterxml.jackson.annotation.JsonProperty("nombre")
    public String getName() { return name != null ? name : (conceptCode != null ? conceptCode.getDescription() : ""); }
    public String name() { return getName(); }
    public void setName(String name) { this.name = name; }

    @com.fasterxml.jackson.annotation.JsonProperty("numeroVersion")
    public int getVersionNumber() { return versionNumber; }
    public int versionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public void closeValidity(LocalDate toDate) {
        this.validTo = toDate;
    }

    public boolean active() { return isActive(); }
    public RecordStatus getStatus() { return status; }
    public void setStatus(RecordStatus status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public void deactivate() { this.status = RecordStatus.INACTIVO; }
    public void activate() { this.status = RecordStatus.ACTIVO; }
}
