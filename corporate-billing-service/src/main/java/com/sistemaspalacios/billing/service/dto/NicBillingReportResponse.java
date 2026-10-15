package com.sistemaspalacios.billing.service.dto;

import java.math.BigDecimal;
import java.util.List;

public record NicBillingReportResponse(
        String periodo,
        int totalNics,
        BigDecimal granTotalFacturado,
        List<NicReportItemDto> items
) {}
