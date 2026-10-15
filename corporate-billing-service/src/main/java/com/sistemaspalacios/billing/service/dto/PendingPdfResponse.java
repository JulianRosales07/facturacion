package com.sistemaspalacios.billing.service.dto;

import com.sistemaspalacios.billing.domain.model.ReceivedPdf;

import java.time.Instant;
import java.util.UUID;

public record PendingPdfResponse(
        UUID id,
        String nombreOriginal,
        Instant recibidoEn,
        int intentos,
        String motivo,
        long tamanoBytes
) {
    public static PendingPdfResponse from(ReceivedPdf entity) {
        return new PendingPdfResponse(
                entity.getId(),
                entity.getOriginalName(),
                entity.getReceivedAt(),
                entity.getAttempts(),
                entity.getReason(),
                entity.getSizeBytes()
        );
    }
}
