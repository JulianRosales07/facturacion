package com.sistemaspalacios.billing.domain.vo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/**
 * Como el convertidor global de Money (infraestructura), pero conserva el NULL. Para columnas donde "sin valor" no es
 * lo mismo que cero: el impacto de una novedad que requiere revisión, la tarifa anterior
 * desconocida o la ausencia de un descuento por valor. Se aplica con {@code @Convert}
 * explícito; el convertidor global sigue tratando NULL como cero.
 */
@Converter
public class NullableMoneyConverter implements AttributeConverter<Money, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.getAmount();
    }

    @Override
    public Money convertToEntityAttribute(BigDecimal dbData) {
        return dbData == null ? null : Money.of(dbData);
    }
}
