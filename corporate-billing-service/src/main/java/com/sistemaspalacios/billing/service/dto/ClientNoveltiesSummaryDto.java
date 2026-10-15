package com.sistemaspalacios.billing.service.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resumen consolidado del consumo de novedades de órdenes para un cliente corporativo en un periodo.
 */
public record ClientNoveltiesSummaryDto(
        String periodo,
        int totalNovedades,
        BigDecimal baseMensual,
        BigDecimal ajusteNovedades,
        BigDecimal cuotasInstalacion,
        BigDecimal totalEstimadoFactura,
        List<ClientNoveltyDetailDto> novedades
) {
    public ClientNoveltiesSummaryDto {
        if (baseMensual == null) baseMensual = BigDecimal.ZERO;
        if (ajusteNovedades == null) ajusteNovedades = BigDecimal.ZERO;
        if (cuotasInstalacion == null) cuotasInstalacion = BigDecimal.ZERO;
        if (totalEstimadoFactura == null) totalEstimadoFactura = baseMensual.add(ajusteNovedades).add(cuotasInstalacion);
        if (novedades == null) novedades = List.of();
    }
}
