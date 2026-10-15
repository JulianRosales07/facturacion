package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps TaxConceptCode enum to SMALLINT concepto_tributario_id (1, 2, 3, 4).
 */
@Converter(autoApply = true)
public class TaxConceptCodeConverter implements AttributeConverter<TaxConceptCode, Short> {

    @Override
    public Short convertToDatabaseColumn(TaxConceptCode attribute) {
        return attribute != null ? (short) attribute.getId() : null;
    }

    @Override
    public TaxConceptCode convertToEntityAttribute(Short dbData) {
        if (dbData == null) return null;
        for (TaxConceptCode code : TaxConceptCode.values()) {
            if (code.getId() == dbData) {
                return code;
            }
        }
        throw new IllegalArgumentException("Unknown TaxConceptCode id: " + dbData);
    }
}
