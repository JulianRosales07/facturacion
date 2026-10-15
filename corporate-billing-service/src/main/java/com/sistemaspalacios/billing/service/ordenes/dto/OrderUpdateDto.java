package com.sistemaspalacios.billing.service.ordenes.dto;

import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data Transfer Object representing an operational novelty/update from the Order System.
 */
public record OrderUpdateDto(
        String externalOrderCode,
        OrderOrigin origin,
        OrderUpdateType updateType,
        String periodCode,
        BigDecimal value,
        LocalDate effectiveDate,
        Boolean includeInConsolidated,
        String observation
) {
    public OrderUpdateDto {
        if (origin == null) origin = OrderOrigin.SPE;
        if (includeInConsolidated == null) includeInConsolidated = true;
        if (value == null) value = BigDecimal.ZERO;
    }
}
