package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentItem;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Line item response DTO for adjustment documents.
 */
public record ItemNotaCreditoResponseDto(
        UUID id,
        UUID itemFacturableId,
        String descripcion,
        BigDecimal cantidad,
        BigDecimal valorUnitario,
        BigDecimal valorDescuento,
        BigDecimal baseGravable,
        BigDecimal porcentajeIva,
        BigDecimal valorIva,
        BigDecimal valorTotal
) {
    public static ItemNotaCreditoResponseDto fromDomain(AdjustmentDocumentItem item) {
        return new ItemNotaCreditoResponseDto(
                item.getId(),
                item.getInvoiceItemId(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice().getAmount(),
                item.getDiscount().getAmount(),
                item.getTaxableBase().getAmount(),
                item.getIvaRate(),
                item.getIvaAmount().getAmount(),
                item.getTotalAmount().getAmount()
        );
    }
}
