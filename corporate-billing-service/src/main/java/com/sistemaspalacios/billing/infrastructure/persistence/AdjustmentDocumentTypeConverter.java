package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps AdjustmentDocumentType enum to PostgreSQL tipo_documento_ajuste ('NOTA_CREDITO', 'NOTA_DEBITO').
 */
@Converter(autoApply = true)
public class AdjustmentDocumentTypeConverter implements AttributeConverter<AdjustmentDocumentType, String> {

    @Override
    public String convertToDatabaseColumn(AdjustmentDocumentType attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public AdjustmentDocumentType convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (AdjustmentDocumentType type : AdjustmentDocumentType.values()) {
            if (type.getDbValue().equalsIgnoreCase(dbData) || type.name().equalsIgnoreCase(dbData)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown AdjustmentDocumentType: " + dbData);
    }
}
