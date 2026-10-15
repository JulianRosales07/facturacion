package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

/**
 * Accounting account mapping for double-entry bookkeeping (mapeo_cuenta_contable).
 */
@Entity
@Table(name = "mapeo_cuenta_contable")
public class AccountingAccountMapping {

    @Id
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_linea", nullable = false)
    private AccountingLineType lineType;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_facturacion")
    private BillingType billingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_item")
    private BillableItemType itemType;

    @Column(name = "marca_id")
    private Short brandId;

    @Column(name = "cuenta_contable", nullable = false, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "naturaleza", nullable = false)
    private AccountingNature nature;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private RecordStatus status = RecordStatus.ACTIVO;

    protected AccountingAccountMapping() {
        // JPA
    }

    public AccountingAccountMapping(
            UUID id,
            AccountingLineType lineType,
            BillingType billingType,
            BillableItemType itemType,
            Short brandId,
            String accountNumber,
            AccountingNature nature,
            RecordStatus status
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.lineType = Objects.requireNonNull(lineType, "lineType cannot be null");
        this.billingType = billingType;
        this.itemType = itemType;
        this.brandId = brandId;
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber cannot be null");
        this.nature = Objects.requireNonNull(nature, "nature cannot be null");
        this.status = status != null ? status : RecordStatus.ACTIVO;
    }

    public UUID getId() { return id; }
    public AccountingLineType getLineType() { return lineType; }
    public BillingType getBillingType() { return billingType; }
    public BillableItemType getItemType() { return itemType; }
    public Short getBrandId() { return brandId; }
    public String getAccountNumber() { return accountNumber; }
    public AccountingNature getNature() { return nature; }
    public RecordStatus getStatus() { return status; }
    public boolean isActive() { return status == RecordStatus.ACTIVO; }

    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public void setNature(AccountingNature nature) { this.nature = nature; }
    public void setStatus(RecordStatus status) { this.status = status; }
}
