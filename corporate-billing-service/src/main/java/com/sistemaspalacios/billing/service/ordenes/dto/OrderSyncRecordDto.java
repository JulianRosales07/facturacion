package com.sistemaspalacios.billing.service.ordenes.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Composite record representing a raw line in file imports or unified API synchronization.
 */
public record OrderSyncRecordDto(
        OrderOrigin origin,
        String nit,
        String businessName,
        String externalOrderCode,
        String nic,
        String siteName,
        String address,
        BigDecimal monthlyValue,
        LocalDate certificationDate,
        Short brandId,
        Short zoneId,
        BillingType billingType,
        String municipality,
        Boolean appliesWithholding,
        String billingEmail,
        String phone,
        BigDecimal installationTotalAmount,
        Integer installationTotalInstallments,
        BigDecimal installationInstallmentAmount,
        OrderUpdateType updateType,
        BigDecimal updateValue,
        LocalDate updateEffectiveDate,
        String updateObservation
) {
    public OrderSyncRecordDto {
        if (origin == null) origin = OrderOrigin.SPE;
        if (brandId == null) brandId = (short) 1;
        if (zoneId == null) zoneId = (short) 1;
        if (billingType == null) billingType = BillingType.F1;
        if (appliesWithholding == null) appliesWithholding = false;
        if (monthlyValue == null) monthlyValue = BigDecimal.ZERO;
    }

    public SubscriptionOrderDto toSubscriptionOrderDto() {
        return new SubscriptionOrderDto(
                origin,
                nit,
                businessName,
                externalOrderCode,
                nic,
                siteName,
                address,
                monthlyValue,
                certificationDate,
                brandId,
                zoneId,
                billingType,
                municipality,
                appliesWithholding,
                billingEmail,
                phone,
                installationTotalAmount,
                installationTotalInstallments,
                installationInstallmentAmount
        );
    }

    public OrderUpdateDto toOrderUpdateDto(String periodCode) {
        if (updateType == null) return null;
        return new OrderUpdateDto(
                externalOrderCode,
                origin,
                updateType,
                periodCode,
                updateValue,
                updateEffectiveDate != null ? updateEffectiveDate : certificationDate,
                true,
                updateObservation
        );
    }
}
