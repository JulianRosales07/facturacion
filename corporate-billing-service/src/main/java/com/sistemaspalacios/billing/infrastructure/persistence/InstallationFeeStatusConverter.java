package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps InstallationFeeStatus enum to PostgreSQL estado_cuota ('PENDIENTE', 'FACTURANDO', 'PAGADA', 'ANULADA').
 */
@Converter(autoApply = true)
public class InstallationFeeStatusConverter implements AttributeConverter<InstallationFeeStatus, String> {

    @Override
    public String convertToDatabaseColumn(InstallationFeeStatus attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public InstallationFeeStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (InstallationFeeStatus status : InstallationFeeStatus.values()) {
            if (status.getDbValue().equalsIgnoreCase(dbData) || status.name().equalsIgnoreCase(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown InstallationFeeStatus: " + dbData);
    }
}
