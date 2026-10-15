package com.sistemaspalacios.billing.service.dto;

import com.sistemaspalacios.billing.domain.vo.Money;

import java.util.UUID;

/**
 * Resultado de incorporar las novedades del periodo a la factura en preparación de un cliente.
 *
 * @param outcome        qué pasó con la factura
 * @param invoiceId      factura afectada; {@code null} si el cliente aún no tiene factura del periodo
 * @param invoiceNumber  número de la factura afectada
 * @param previousTotal  total antes de incorporar las novedades
 * @param newTotal       total después; igual al anterior si no se modificó
 * @param itemCount      conceptos que quedaron en la factura
 * @param detail         explicación legible, en particular el motivo cuando no es editable
 */
public record InvoiceRefreshResult(
        Outcome outcome,
        UUID invoiceId,
        String invoiceNumber,
        Money previousTotal,
        Money newTotal,
        int itemCount,
        String detail
) {
    public enum Outcome {
        /** La factura en preparación se recalculó con las novedades. */
        ACTUALIZADA,
        /** El cliente no tiene factura del periodo; las novedades entrarán al generarla. */
        SIN_FACTURA,
        /** Hay factura pero ya no está en preparación; las novedades quedan pendientes. */
        NO_EDITABLE
    }

    public String toMessage() {
        return switch (outcome) {
            case ACTUALIZADA -> String.format("Factura %s actualizada con las novedades: total %s → %s (%d conceptos).",
                    invoiceNumber, previousTotal.amount().toPlainString(), newTotal.amount().toPlainString(), itemCount);
            case SIN_FACTURA -> detail;
            case NO_EDITABLE -> String.format("Factura %s no se modificó: %s.", invoiceNumber, detail);
        };
    }
}
