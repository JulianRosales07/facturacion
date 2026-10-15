package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;

import java.time.Instant;
import java.util.UUID;

public record LoteResponseDto(
        UUID id,
        UUID periodoId,
        BillingType tipoFacturacion,
        String tipoCorte,
        String nombreArchivo,
        int totalFacturas,
        ExportBatchStatus estado,
        Instant generadoEn,
        Instant confirmadoEn
) {
    public static LoteResponseDto fromEntity(ExportBatch batch) {
        return new LoteResponseDto(
                batch.getId(),
                batch.getPeriodId(),
                batch.getBillingType(),
                batch.getCutType(),
                batch.getFileName(),
                batch.getTotalInvoices(),
                batch.getStatus(),
                batch.getGeneratedAt(),
                batch.getConfirmedAt()
        );
    }
}
