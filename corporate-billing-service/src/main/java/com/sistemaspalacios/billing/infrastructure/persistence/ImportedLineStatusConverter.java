package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.ImportedLineStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps ImportedLineStatus enum to PostgreSQL estado_linea_importada ('PENDIENTE', 'PROCESADA', 'ERROR').
 */
@Converter(autoApply = true)
public class ImportedLineStatusConverter implements AttributeConverter<ImportedLineStatus, String> {

    @Override
    public String convertToDatabaseColumn(ImportedLineStatus attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public ImportedLineStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (ImportedLineStatus status : ImportedLineStatus.values()) {
            if (status.getDbValue().equalsIgnoreCase(dbData) || status.name().equalsIgnoreCase(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown ImportedLineStatus: " + dbData);
    }
}
