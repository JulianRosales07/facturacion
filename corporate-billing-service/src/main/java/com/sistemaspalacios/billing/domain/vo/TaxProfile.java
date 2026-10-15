package com.sistemaspalacios.billing.domain.vo;

import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tax profile assigned to a corporate client defining which withholdings apply
 * and optional agreed percentage overrides.
 */
public record TaxProfile(
        boolean appliesWithholding,
        String municipality,
        List<TaxProfileItem> items
) {
    public TaxProfile {
        if (items == null) {
            items = List.of();
        } else {
            items = List.copyOf(items);
        }
    }

    public static TaxProfile withoutWithholding(String municipality) {
        return new TaxProfile(false, municipality, List.of());
    }

    public Optional<TaxProfileItem> findItemForConcept(TaxConceptCode code) {
        return items.stream()
                .filter(item -> item.conceptCode() == code)
                .findFirst();
    }

    public record TaxProfileItem(
            TaxConceptCode conceptCode,
            ServiceClassification classification,
            BigDecimal percentageOverride
    ) {
        public TaxProfileItem {
            Objects.requireNonNull(conceptCode, "conceptCode cannot be null");
        }
    }
}
