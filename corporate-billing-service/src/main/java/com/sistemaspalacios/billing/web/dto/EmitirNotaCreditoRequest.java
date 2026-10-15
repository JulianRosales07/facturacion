package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.CreditNoteService.AdjustmentItemCommand;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request payload for POST /facturas/{id}/notas-credito (HU-20).
 */
public record EmitirNotaCreditoRequest(
        @NotBlank(message = "El motivo de la nota crédito es obligatorio")
        String motivo,

        String numeroDocumento,

        List<ItemNotaCreditoRequest> items
) {
    public List<AdjustmentItemCommand> toCommands() {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(item -> new AdjustmentItemCommand(
                        item.itemFacturableId(),
                        item.descripcion(),
                        item.cantidad(),
                        item.valorUnitario() != null ? Money.of(item.valorUnitario()) : null,
                        item.valorDescuento() != null ? Money.of(item.valorDescuento()) : null,
                        item.porcentajeIva()
                ))
                .toList();
    }
}
