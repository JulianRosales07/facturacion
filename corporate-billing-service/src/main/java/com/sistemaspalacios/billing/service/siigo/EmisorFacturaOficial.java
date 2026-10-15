package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.Invoice;

import java.util.List;

/**
 * Alias en español para el puerto OfficialInvoiceEmitter (regla DDD-Lite).
 */
public interface EmisorFacturaOficial extends OfficialInvoiceEmitter {

    default byte[] exportarLote(ExportBatch batch, List<Invoice> facturas) {
        return exportBatch(batch, facturas);
    }
}
