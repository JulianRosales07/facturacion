package com.sistemaspalacios.billing.service.dto;

import java.math.BigDecimal;

public record NicReportItemDto(
        String nic,
        String nombreSede,
        String clientNit,
        String clientBusinessName,
        long cantidadItems,
        BigDecimal totalSubtotal,
        BigDecimal totalIva,
        BigDecimal totalFacturado
) {}
