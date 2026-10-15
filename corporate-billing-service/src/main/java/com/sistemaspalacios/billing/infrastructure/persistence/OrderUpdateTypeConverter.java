package com.sistemaspalacios.billing.infrastructure.persistence;

import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps OrderUpdateType enum to SMALLINT tipo_novedad_id (1..7).
 */
@Converter(autoApply = true)
public class OrderUpdateTypeConverter implements AttributeConverter<OrderUpdateType, Short> {

    @Override
    public Short convertToDatabaseColumn(OrderUpdateType attribute) {
        return attribute != null ? (short) attribute.getId() : null;
    }

    @Override
    public OrderUpdateType convertToEntityAttribute(Short dbData) {
        if (dbData == null) return null;
        for (OrderUpdateType type : OrderUpdateType.values()) {
            if (type.getId() == dbData) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown OrderUpdateType id: " + dbData);
    }
}
