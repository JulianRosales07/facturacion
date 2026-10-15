package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.FileStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps FileStatus enum to PostgreSQL estado_archivo ('RECIBIDO', 'PROCESANDO', 'PROCESADO', 'ERROR').
 */
@Converter(autoApply = true)
public class FileStatusConverter implements AttributeConverter<FileStatus, String> {

    @Override
    public String convertToDatabaseColumn(FileStatus attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public FileStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (FileStatus status : FileStatus.values()) {
            if (status.getDbValue().equalsIgnoreCase(dbData) || status.name().equalsIgnoreCase(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown FileStatus: " + dbData);
    }
}
