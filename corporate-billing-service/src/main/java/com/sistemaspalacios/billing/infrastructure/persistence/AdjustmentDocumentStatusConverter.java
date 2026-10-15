package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps AdjustmentDocumentStatus enum to PostgreSQL estado_documento_ajuste ('BORRADOR', 'APLICADO', 'ANULADO').
 */
@Converter(autoApply = true)
public class AdjustmentDocumentStatusConverter implements AttributeConverter<AdjustmentDocumentStatus, String> {

    @Override
    public String convertToDatabaseColumn(AdjustmentDocumentStatus attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public AdjustmentDocumentStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (AdjustmentDocumentStatus status : AdjustmentDocumentStatus.values()) {
            if (status.getDbValue().equalsIgnoreCase(dbData) || status.name().equalsIgnoreCase(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown AdjustmentDocumentStatus: " + dbData);
    }
}
