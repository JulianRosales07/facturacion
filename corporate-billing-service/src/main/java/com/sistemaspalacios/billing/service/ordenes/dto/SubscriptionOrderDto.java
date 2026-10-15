package com.sistemaspalacios.billing.service.ordenes.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data Transfer Object representing a subscription site received from the Order System.
 */
public record SubscriptionOrderDto(
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
        BigDecimal installationInstallmentAmount
) {
    public SubscriptionOrderDto {
        if (origin == null) origin = OrderOrigin.SPE;
        if (brandId == null) brandId = (short) 1;
        if (zoneId == null) zoneId = (short) 1;
        if (billingType == null) billingType = BillingType.F1;
        if (appliesWithholding == null) appliesWithholding = false;
        if (monthlyValue == null) monthlyValue = BigDecimal.ZERO;
    }
}
