package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import java.io.InputStream;
import java.util.List;

public interface OfficialInvoiceReader {
    /** Una hoja de otro tipo devuelve una lista vacía; un documento inválido falla explícitamente. */
    List<OfficialInvoiceData> read(String filename, InputStream input);
}
