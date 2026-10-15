package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for credit note and adjustment documents (Sprint 5).
 */
public record NotaCreditoResponseDto(
        UUID id,
        UUID facturaId,
        String tipo,
        String numeroDocumento,
        String motivo,
        BigDecimal valorBruto,
        BigDecimal valorDescuento,
        BigDecimal valorSubtotal,
        BigDecimal valorIva,
        BigDecimal valorNeto,
        String estado,
        Instant creadoEn,
        List<ItemNotaCreditoResponseDto> items
) {
    public static NotaCreditoResponseDto fromDomain(AdjustmentDocument doc) {
        List<ItemNotaCreditoResponseDto> itemDtos = doc.getItems().stream()
                .map(ItemNotaCreditoResponseDto::fromDomain)
                .toList();

        return new NotaCreditoResponseDto(
                doc.getId(),
                doc.getInvoiceId(),
                doc.getType().name(),
                doc.getDocumentNumber(),
                doc.getReason(),
                doc.getGrossAmount().getAmount(),
                doc.getDiscountAmount().getAmount(),
                doc.getSubtotalAmount().getAmount(),
                doc.getIvaAmount().getAmount(),
                doc.getNetAmount().getAmount(),
                doc.getStatus().name(),
                doc.getCreatedAt(),
                itemDtos
        );
    }
}
