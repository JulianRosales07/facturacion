package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Una fila del corte de cartera de Siigo, con el resultado de intentar aplicarla
 * (Sprint 13).
 * <p>
 * Se persisten **todas** las filas, incluidas las que no encontraron factura. Antes de esta
 * tabla, una fila huérfana solo quedaba en el log y nadie se enteraba de que Siigo reportaba
 * un documento que el microservicio no conoce.
 */
@Entity
@Table(name = "cartera_snapshot_linea")
public class CarteraSnapshotLine {

    public static final String STATUS_APPLIED = "APLICADA";
    public static final String STATUS_NO_INVOICE = "SIN_FACTURA";
    public static final String STATUS_NIT_MISMATCH = "NIT_DISCREPANTE";
    public static final String STATUS_STALE_SNAPSHOT = "CORTE_OBSOLETO";
    public static final String STATUS_FORMAT_ERROR = "ERROR_FORMATO";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "cartera_snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "numero_fila", nullable = false)
    private int rowNumber;

    @Column(name = "nit_archivo", length = 20)
    private String fileNit;

    @Column(name = "cuenta_contable", length = 20)
    private String accountingAccount;

    @Column(name = "tipo_comprobante", length = 5)
    private String voucherType;

    @Column(name = "codigo_comprobante")
    private Integer voucherCode;

    @Column(name = "numero_documento", length = 30)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_facturacion")
    private BillingType billingType;

    @Column(name = "saldo", precision = 14, scale = 2)
    private Money balance;

    /** Saldo que el microservicio tenía calculado al aplicar esta fila. */
    @Column(name = "saldo_calculado", precision = 14, scale = 2)
    private Money computedBalance;

    /** {@code saldo} − {@code saldo_calculado}. Congelado para que conciliar sea solo leer. */
    @Column(name = "diferencia", precision = 14, scale = 2)
    private Money difference;

    @Column(name = "fecha_vencimiento")
    private LocalDate dueDate;

    @Column(name = "fecha_documento")
    private LocalDate documentDate;

    @Column(name = "centro_costo", length = 20)
    private String costCenter;

    @Column(name = "codigo_ciudad", length = 20)
    private String cityCode;

    @Column(name = "codigo_zona", length = 20)
    private String zoneCode;

    @Column(name = "factura_id")
    private UUID invoiceId;

    @Column(name = "estado_linea", nullable = false, length = 30)
    private String lineStatus = STATUS_NO_INVOICE;

    @Column(name = "mensaje", length = 500)
    private String message;

    protected CarteraSnapshotLine() {
        // JPA
    }

    public CarteraSnapshotLine(UUID id, int rowNumber) {
        this.id = id != null ? id : UUID.randomUUID();
        this.rowNumber = rowNumber;
    }

    /** Fila que no se pudo interpretar; se conserva para que el error sea visible. */
    public static CarteraSnapshotLine formatError(int rowNumber, String message) {
        CarteraSnapshotLine line = new CarteraSnapshotLine(null, rowNumber);
        line.lineStatus = STATUS_FORMAT_ERROR;
        line.message = message;
        return line;
    }

    /**
     * Marca la fila como aplicada y congela la comparación contra el saldo local.
     *
     * @param computedBalance saldo que el microservicio tenía calculado antes de aplicar
     */
    public void markApplied(UUID invoiceId, Money computedBalance) {
        this.invoiceId = invoiceId;
        this.lineStatus = STATUS_APPLIED;
        this.computedBalance = computedBalance;
        if (this.balance != null && computedBalance != null) {
            this.difference = this.balance.minus(computedBalance);
        }
    }

    /** {@code true} si Siigo y el microservicio no coincidían al aplicar esta fila. */
    public boolean hasDiscrepancy() {
        return difference != null && !difference.isZero();
    }

    public void markNoInvoice(String message) {
        this.lineStatus = STATUS_NO_INVOICE;
        this.message = message;
    }

    public void markNitMismatch(UUID invoiceId, String message) {
        this.invoiceId = invoiceId;
        this.lineStatus = STATUS_NIT_MISMATCH;
        this.message = message;
    }

    public void markStaleSnapshot(UUID invoiceId, String message) {
        this.invoiceId = invoiceId;
        this.lineStatus = STATUS_STALE_SNAPSHOT;
        this.message = message;
    }

    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }

    public void setFileNit(String fileNit) { this.fileNit = fileNit; }
    public void setAccountingAccount(String accountingAccount) { this.accountingAccount = accountingAccount; }
    public void setVoucherType(String voucherType) { this.voucherType = voucherType; }
    public void setVoucherCode(Integer voucherCode) { this.voucherCode = voucherCode; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public void setBillingType(BillingType billingType) { this.billingType = billingType; }
    public void setBalance(Money balance) { this.balance = balance; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setDocumentDate(LocalDate documentDate) { this.documentDate = documentDate; }
    public void setCostCenter(String costCenter) { this.costCenter = costCenter; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }

    public UUID getId() { return id; }
    public UUID getSnapshotId() { return snapshotId; }
    public int getRowNumber() { return rowNumber; }
    public String getFileNit() { return fileNit; }
    public String getAccountingAccount() { return accountingAccount; }
    public String getVoucherType() { return voucherType; }
    public Integer getVoucherCode() { return voucherCode; }
    public String getDocumentNumber() { return documentNumber; }
    public BillingType getBillingType() { return billingType; }
    public Money getBalance() { return balance; }
    public Money getComputedBalance() { return computedBalance; }
    public Money getDifference() { return difference; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getDocumentDate() { return documentDate; }
    public String getCostCenter() { return costCenter; }
    public String getCityCode() { return cityCode; }
    public String getZoneCode() { return zoneCode; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getLineStatus() { return lineStatus; }
    public String getMessage() { return message; }
}
