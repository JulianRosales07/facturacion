package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Installation Fee billed in installments across multiple periods (cuota_instalacion).
 */
@Entity
@Table(name = "cuota_instalacion")
public class InstallationFee {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "sede_suscripcion_id", nullable = false)
    private UUID siteId;

    @Column(name = "valor_total", nullable = false, precision = 14, scale = 2)
    private Money totalAmount;

    @Column(name = "numero_cuotas", nullable = false)
    private int totalInstallments;

    @Column(name = "cuotas_facturadas", nullable = false)
    private int billedInstallments;

    @Column(name = "valor_cuota", nullable = false, precision = 14, scale = 2)
    private Money installmentAmount;

    @Column(name = "estado", nullable = false)
    private InstallationFeeStatus status;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected InstallationFee() {
        // JPA
    }

    public InstallationFee(
            UUID id,
            UUID siteId,
            Money totalAmount,
            int totalInstallments,
            int billedInstallments,
            Money installmentAmount,
            InstallationFeeStatus status
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.siteId = Objects.requireNonNull(siteId, "siteId cannot be null");
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount cannot be null");
        if (totalInstallments <= 0) {
            throw new IllegalArgumentException("totalInstallments must be > 0");
        }
        this.totalInstallments = totalInstallments;
        this.billedInstallments = billedInstallments;
        this.installmentAmount = Objects.requireNonNull(installmentAmount, "installmentAmount cannot be null");
        this.status = status != null ? status : InstallationFeeStatus.PENDING;
    }

    public boolean hasPendingInstallments() {
        return billedInstallments < totalInstallments && status != InstallationFeeStatus.PAID && status != InstallationFeeStatus.VOIDED;
    }

    public void incrementBilledInstallments() {
        if (!hasPendingInstallments()) {
            throw new IllegalStateException("No pending installments to bill");
        }
        this.billedInstallments++;
        if (this.billedInstallments >= this.totalInstallments) {
            this.status = InstallationFeeStatus.PAID;
        } else {
            this.status = InstallationFeeStatus.BILLING;
        }
    }

    /**
     * Devuelve cuotas al pool de pendientes (Sprint 10).
     * <p>
     * Se usa al regenerar una factura: las cuotas que consumió la versión reemplazada
     * deben liberarse antes de recalcular, de lo contrario la nueva versión avanzaría
     * el contador por segunda vez y el cliente pagaría la misma cuota dos veces.
     *
     * @param count número de cuotas a liberar; se ignora si es &lt;= 0
     * @throws IllegalStateException si se intenta liberar más cuotas de las facturadas
     */
    public void rollbackBilledInstallments(int count) {
        if (count <= 0) {
            return;
        }
        if (count > this.billedInstallments) {
            throw new IllegalStateException(String.format(
                    "No se pueden liberar %d cuotas: solo %d están facturadas en la cuota de instalación %s",
                    count, this.billedInstallments, this.id));
        }
        if (this.status == InstallationFeeStatus.VOIDED) {
            throw new IllegalStateException("No se pueden liberar cuotas de una cuota de instalación anulada");
        }
        this.billedInstallments -= count;
        this.status = (this.billedInstallments == 0)
                ? InstallationFeeStatus.PENDING
                : InstallationFeeStatus.BILLING;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public Money getTotalAmount() { return totalAmount; }
    public int getTotalInstallments() { return totalInstallments; }
    public int getBilledInstallments() { return billedInstallments; }
    public Money getInstallmentAmount() { return installmentAmount; }
    public InstallationFeeStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
