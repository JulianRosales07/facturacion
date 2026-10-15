package com.sistemaspalacios.billing.domain.vo;

import java.math.BigDecimal;
import java.util.UUID;

/** Ajuste explícito del pagador para un concepto del catálogo; no altera el catálogo. */
public record PaymentDeduction(UUID validityId, BigDecimal percentage, Money amount, String reason) {
    public PaymentDeduction {
        if (validityId == null || (percentage == null) == (amount == null)) {
            throw new IllegalArgumentException("Indique un concepto y solo porcentaje o importe");
        }
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new IllegalArgumentException("Justifique el ajuste de retención (máximo 255 caracteres)");
        }
        if (percentage != null && (percentage.signum() < 0 || percentage.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("Porcentaje fuera del rango 0 a 100");
        }
        if (amount != null && amount.isNegative()) throw new IllegalArgumentException("Importe negativo");
    }
}
