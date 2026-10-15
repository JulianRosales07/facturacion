package com.sistemaspalacios.billing.service.dto;

import java.util.List;

public record NitRecordDto(
        String nit,
        String clientName,
        int branchesCount,
        String totalAmount,
        String status,
        List<String> services
) {}
