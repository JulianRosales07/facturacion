package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;

import java.util.List;
import java.util.UUID;

/**
 * Interfaz en español para el servicio de notas crédito y documentos de ajuste (Sprint 5, HU-20).
 */
public interface ServicioNotaCredito extends CreditNoteService {

    default AdjustmentDocument emitir(UUID facturaId, String motivo, String numeroDocumento, List<AdjustmentItemCommand> items) {
        return issueCreditNote(facturaId, motivo, numeroDocumento, items);
    }

    default List<AdjustmentDocument> consultarPorFactura(UUID facturaId) {
        return getCreditNotesByInvoice(facturaId);
    }
}
