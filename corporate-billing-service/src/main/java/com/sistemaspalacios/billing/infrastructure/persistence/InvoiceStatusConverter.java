package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps InvoiceStatus enum to PostgreSQL estado_factura ('BORRADOR', 'CONSOLIDADA', 'PAGADA', 'ANULADA').
 */
@Converter(autoApply = true)
public class InvoiceStatusConverter implements AttributeConverter<InvoiceStatus, String> {

    @Override
    public String convertToDatabaseColumn(InvoiceStatus attribute) {
        return attribute != null ? attribute.getDbValue() : null;
    }

    @Override
    public InvoiceStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        for (InvoiceStatus status : InvoiceStatus.values()) {
            if (status.getDbValue().equalsIgnoreCase(dbData) || status.name().equalsIgnoreCase(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown InvoiceStatus: " + dbData);
    }
}
