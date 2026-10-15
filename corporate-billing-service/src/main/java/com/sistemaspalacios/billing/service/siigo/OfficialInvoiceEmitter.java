package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.Invoice;

import java.util.List;

/**
 * Port for exporting official billing vouchers (Siigo flat file now, DIAN XML/CUFE in future).
 */
public interface OfficialInvoiceEmitter {

    /**
     * Exports a batch of invoices into official flat file bytes.
     *
     * @param batch The export batch header
     * @param invoices List of invoices belonging to this batch
     * @return Raw bytes encoded for official transmission (e.g. ISO-8859-1 or UTF-8)
     */
    byte[] exportBatch(ExportBatch batch, List<Invoice> invoices);
}
