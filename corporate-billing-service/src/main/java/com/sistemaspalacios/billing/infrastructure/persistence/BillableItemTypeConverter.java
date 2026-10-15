package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps BillableItemType enum to PostgreSQL tipo_item_facturable ('SERVICIO_CORPORATIVO', 'SVA_STREAMING', 'SVA_PRODUCTO').
 */
@Converter(autoApply = true)
public class BillableItemTypeConverter implements AttributeConverter<BillableItemType, String> {

    @Override
    public String convertToDatabaseColumn(BillableItemType attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public BillableItemType convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (BillableItemType type : BillableItemType.values()) {
            if (type.getDbValue().equalsIgnoreCase(dbData) || type.name().equalsIgnoreCase(dbData)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown BillableItemType: " + dbData);
    }
}
