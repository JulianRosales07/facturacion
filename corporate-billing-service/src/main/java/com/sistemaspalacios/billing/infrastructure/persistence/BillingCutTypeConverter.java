package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts BillingCutType enum to VARCHAR ("ADELANTADO", "VENCIDO").
 */
@Converter(autoApply = true)
public class BillingCutTypeConverter implements AttributeConverter<BillingCutType, String> {

    @Override
    public String convertToDatabaseColumn(BillingCutType attribute) {
        return attribute != null ? attribute.getSpanishName() : null;
    }

    @Override
    public BillingCutType convertToEntityAttribute(String dbData) {
        return dbData != null ? BillingCutType.fromSpanish(dbData) : null;
    }
}
