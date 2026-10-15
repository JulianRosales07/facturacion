package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Corporate B2B Client identified by NIT (cliente_corporativo).
 */
@Entity
@Table(name = "cliente_corporativo")
public class CorporateClient {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nit", nullable = false, unique = true, length = 20)
    private String nit;

    @Column(name = "razon_social", nullable = false, length = 255)
    private String businessName;

    @Column(name = "direccion_fiscal", length = 255)
    private String taxAddress;

    @Column(name = "email_facturacion", length = 150)
    private String billingEmail;

    @Column(name = "telefono", length = 50)
    private String phone;

    @Column(name = "marca_id", nullable = false)
    private short brandId;

    @Column(name = "zona_id", nullable = false)
    private short zoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_facturacion", nullable = false)
    private BillingType billingType = BillingType.F1;

    @Column(name = "aplica_retencion", nullable = false)
    private boolean appliesWithholding;

    @Column(name = "municipio", length = 60)
    private String municipality;

    @Column(name = "codigo_vendedor")
    private Integer sellerCode;

    @Column(name = "centro_costo")
    private Integer costCenter;

    @Column(name = "subcentro_costo")
    private Integer subCostCenter;

    @Column(name = "codigo_ciudad")
    private Integer cityCode;

    @Column(name = "codigo_zona_siigo")
    private Integer siigoZoneCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private RecordStatus status = RecordStatus.ACTIVO;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "regla_facturacion", nullable = false)
    private ClientBillingRule billingRule = ClientBillingRule.ORDINARIA;

    @Column(name = "detalle_regla_corporativa", length = 255)
    private String corporateRuleDetails;

    @Column(name = "dia_corte_corporativo")
    private Integer corporateCutoffDay;

    @Transient
    private TaxProfile taxProfile;

    protected CorporateClient() {
        // JPA
    }

    public CorporateClient(
            UUID id,
            String nit,
            String businessName,
            String taxAddress,
            String billingEmail,
            String phone,
            short brandId,
            short zoneId,
            BillingType billingType,
            boolean appliesWithholding,
            String municipality,
            TaxProfile taxProfile
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.nit = Objects.requireNonNull(nit, "nit cannot be null");
        this.businessName = Objects.requireNonNull(businessName, "businessName cannot be null");
        this.taxAddress = taxAddress;
        this.billingEmail = billingEmail;
        this.phone = phone;
        this.brandId = brandId;
        this.zoneId = zoneId;
        this.billingType = billingType != null ? billingType : BillingType.F1;
        this.appliesWithholding = appliesWithholding;
        this.municipality = municipality;
        this.taxProfile = taxProfile != null ? taxProfile : TaxProfile.withoutWithholding(municipality);
        this.status = RecordStatus.ACTIVO;
    }

    public UUID getId() { return id; }
    public String getNit() { return nit; }
    public void setNit(String nit) { this.nit = nit; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getTaxAddress() { return taxAddress; }
    public void setTaxAddress(String taxAddress) { this.taxAddress = taxAddress; }
    public String getBillingEmail() { return billingEmail; }
    public void setBillingEmail(String billingEmail) { this.billingEmail = billingEmail; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public short getBrandId() { return brandId; }
    public void setBrandId(short brandId) { this.brandId = brandId; }
    public short getZoneId() { return zoneId; }
    public void setZoneId(short zoneId) { this.zoneId = zoneId; }
    public BillingType getBillingType() { return billingType; }
    public void setBillingType(BillingType billingType) { this.billingType = billingType; }
    public boolean isAppliesWithholding() { return appliesWithholding; }
    public void setAppliesWithholding(boolean appliesWithholding) { this.appliesWithholding = appliesWithholding; }
    public String getMunicipality() { return municipality; }
    public void setMunicipality(String municipality) { this.municipality = municipality; }
    public Integer getSellerCode() { return sellerCode; }
    public void setSellerCode(Integer sellerCode) { this.sellerCode = sellerCode; }
    public Integer getCostCenter() { return costCenter; }
    public void setCostCenter(Integer costCenter) { this.costCenter = costCenter; }
    public Integer getSubCostCenter() { return subCostCenter; }
    public void setSubCostCenter(Integer subCostCenter) { this.subCostCenter = subCostCenter; }
    public Integer getCityCode() { return cityCode; }
    public void setCityCode(Integer cityCode) { this.cityCode = cityCode; }
    public Integer getSiigoZoneCode() { return siigoZoneCode; }
    public void setSiigoZoneCode(Integer siigoZoneCode) { this.siigoZoneCode = siigoZoneCode; }
    public TaxProfile getTaxProfile() { return taxProfile; }
    public void setTaxProfile(TaxProfile taxProfile) { this.taxProfile = taxProfile; }

    public ClientBillingRule getBillingRule() { return billingRule != null ? billingRule : ClientBillingRule.ORDINARIA; }
    public void setBillingRule(ClientBillingRule billingRule) { this.billingRule = billingRule != null ? billingRule : ClientBillingRule.ORDINARIA; }
    public String getCorporateRuleDetails() { return corporateRuleDetails; }
    public void setCorporateRuleDetails(String corporateRuleDetails) { this.corporateRuleDetails = corporateRuleDetails; }

    public Integer getCorporateCutoffDay() { return corporateCutoffDay; }
    public void setCorporateCutoffDay(Integer corporateCutoffDay) {
        if (corporateCutoffDay != null && (corporateCutoffDay < 1 || corporateCutoffDay > 28)) {
            throw new IllegalArgumentException("El día de corte corporativo debe estar entre 1 y 28 (valor recibido: " + corporateCutoffDay + ")");
        }
        this.corporateCutoffDay = corporateCutoffDay;
    }

    public boolean isConfigurationComplete() {
        if (getBillingRule() == ClientBillingRule.ESPECIAL_CORPORATIVA) {
            return corporateCutoffDay != null && corporateCutoffDay >= 1 && corporateCutoffDay <= 28;
        }
        return true;
    }

    public RecordStatus getStatus() { return status; }
    public void setStatus(RecordStatus status) { this.status = status; }
    public boolean isActive() { return status == RecordStatus.ACTIVO; }
    public void deactivate() { this.status = RecordStatus.INACTIVO; }
    public void activate() { this.status = RecordStatus.ACTIVO; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
