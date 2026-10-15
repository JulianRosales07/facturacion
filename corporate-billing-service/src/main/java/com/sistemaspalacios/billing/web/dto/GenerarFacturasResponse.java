package com.sistemaspalacios.billing.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta del endpoint POST /facturas/generar.
 */
public record GenerarFacturasResponse(
        int generadas,
        BigDecimal valorTotal,
        List<FacturaResumenDto> facturas
) {
    public static GenerarFacturasResponse of(List<FacturaResumenDto> facturas) {
        BigDecimal sum = facturas.stream()
                .map(FacturaResumenDto::valorTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GenerarFacturasResponse(facturas.size(), sum, facturas);
    }
}
