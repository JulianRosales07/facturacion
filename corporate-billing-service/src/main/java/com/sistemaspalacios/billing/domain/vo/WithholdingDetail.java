package com.sistemaspalacios.billing.domain.vo;

import com.sistemaspalacios.billing.domain.model.TaxConceptCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Details of an individual withholding deduction (Retefuente, ReteICA, ReteIVA, Estampillas).
 * Used strictly for net payable calculation, never altering the official invoice total.
 */
public record WithholdingDetail(
        TaxConceptCode conceptCode,
        String description,
        Money taxableBase,
        BigDecimal ratePercentage,
        Money withheldAmount
) {
    public WithholdingDetail {
        Objects.requireNonNull(conceptCode, "conceptCode cannot be null");
        Objects.requireNonNull(taxableBase, "taxableBase cannot be null");
        Objects.requireNonNull(ratePercentage, "ratePercentage cannot be null");
        Objects.requireNonNull(withheldAmount, "withheldAmount cannot be null");
    }
}
