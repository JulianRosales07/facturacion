package com.sistemaspalacios.billing.domain.vo;

import com.sistemaspalacios.billing.domain.model.TaxConceptCode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of calculating withholdings at payment settlement time (Sprint 9, HU-51, HU-52).
 */
public record WithholdingCalculationResult(
        Money grossAmount,
        Money totalTaxableBase,
        Money totalIva,
        Money totalWithholdings,
        Money rawNetPayable,
        Money roundedNetPayable,
        Money roundingDifference,
        List<WithholdingItemEvaluation> evaluations
) {
    public WithholdingCalculationResult {
        Objects.requireNonNull(grossAmount, "grossAmount cannot be null");
        Objects.requireNonNull(totalTaxableBase, "totalTaxableBase cannot be null");
        Objects.requireNonNull(totalIva, "totalIva cannot be null");
        Objects.requireNonNull(totalWithholdings, "totalWithholdings cannot be null");
        Objects.requireNonNull(rawNetPayable, "rawNetPayable cannot be null");
        Objects.requireNonNull(roundedNetPayable, "roundedNetPayable cannot be null");
        Objects.requireNonNull(roundingDifference, "roundingDifference cannot be null");
        evaluations = evaluations != null ? Collections.unmodifiableList(evaluations) : List.of();
    }

    public record WithholdingItemEvaluation(
            UUID validityId,
            int versionNumber,
            TaxConceptCode conceptCode,
            String conceptName,
            BigDecimal percentage,
            Money minimumBase,
            Money calculationBase,
            Money withheldAmount,
            boolean applicable,
            String nonApplicableReason
    ) {
        public WithholdingItemEvaluation {
            Objects.requireNonNull(conceptCode, "conceptCode cannot be null");
            Objects.requireNonNull(conceptName, "conceptName cannot be null");
            Objects.requireNonNull(percentage, "percentage cannot be null");
            Objects.requireNonNull(minimumBase, "minimumBase cannot be null");
            Objects.requireNonNull(calculationBase, "calculationBase cannot be null");
            Objects.requireNonNull(withheldAmount, "withheldAmount cannot be null");
        }
    }
}
