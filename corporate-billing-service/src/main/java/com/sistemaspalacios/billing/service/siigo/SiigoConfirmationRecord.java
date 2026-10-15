package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.domain.model.BillingType;

import java.math.BigDecimal;

/**
 * Record representing a single confirmation line/record returned by Siigo.
 */
public record SiigoConfirmationRecord(
        String documentNumber,
        BillingType billingType,
        String clientNit,
        String cufe,
        String deliveryStatus,
        BigDecimal totalAmount
) {
}
