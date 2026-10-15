package com.sistemaspalacios.billing.domain.vo;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Result of a proration calculation for a billing period.
 */
public record ProrationResult(
        int baseDays,
        int effectiveDays,
        BigDecimal factor,
        Money proratedAmount
) {
    public ProrationResult {
        Objects.requireNonNull(factor, "factor cannot be null");
        Objects.requireNonNull(proratedAmount, "proratedAmount cannot be null");
        if (baseDays <= 0) {
            throw new IllegalArgumentException("baseDays must be greater than 0");
        }
        if (effectiveDays < 0) {
            throw new IllegalArgumentException("effectiveDays cannot be negative");
        }
    }
}
