package com.sistemaspalacios.billing.service.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ImportResultDto(
        UUID id,
        String fileName,
        String method,
        String origin,
        String status,
        int totalRecords,
        int errorRecords,
        Instant receivedAt,
        Instant processedAt,
        List<NitRecordDto> records,
        List<String> messages
) {}
