package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Application service interface for credit notes and adjustment documents (Sprint 5, HU-20).
 */
public interface CreditNoteService {

    record AdjustmentItemCommand(
            UUID invoiceItemId,
            String description,
            BigDecimal quantity,
            Money unitPrice,
            Money discount,
            BigDecimal ivaRate
    ) {}

    /**
     * Emite una nota crédito ligada a una factura consolidada.
     * Si la lista de items viene vacía o nula, se genera una nota crédito total con los ítems de la factura.
     */
    AdjustmentDocument issueCreditNote(UUID invoiceId, String reason, String documentNumber, List<AdjustmentItemCommand> items);

    /**
     * Consulta todas las notas de crédito emitidas para una factura dada.
     */
    List<AdjustmentDocument> getCreditNotesByInvoice(UUID invoiceId);

    /**
     * Obtiene el detalle completo de una nota de crédito o documento de ajuste por su ID.
     */
    AdjustmentDocument getCreditNoteById(UUID creditNoteId);
}
