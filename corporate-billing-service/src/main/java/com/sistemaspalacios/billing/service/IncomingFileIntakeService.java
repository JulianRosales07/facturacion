package com.sistemaspalacios.billing.service;

import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** La transacción de importación termina antes de registrar un fallo; la auditoría sobrevive al rollback. */
@Service
public class IncomingFileIntakeService {
    private final OfficialInvoiceImportService importer;
    private final IncomingFileAuditService audit;
    public IncomingFileIntakeService(OfficialInvoiceImportService importer, IncomingFileAuditService audit) {
        this.importer=importer; this.audit=audit;
    }
    public OfficialInvoiceImportService.ImportResult receive(String filename, byte[] bytes, String source) {
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        OfficialInvoiceImportService.ImportResult result;
        try {
            if (bytes.length == 0 || bytes.length > 20*1024*1024) throw new IllegalArgumentException("Archivo vacío o mayor a 20 MB");
            result=importer.importDetailed(filename,new ByteArrayInputStream(bytes));
        } catch (RuntimeException ex) {
            if ("CARPETA".equalsIgnoreCase(source)) {
                audit.recordIfChanged(filename,source,"RECHAZADO",hash,0,0,ex.getMessage());
            } else {
                audit.record(filename,source,"RECHAZADO",hash,0,0,ex.getMessage());
            }
            throw ex;
        }
        audit.record(filename,source,result.invoiceIds().isEmpty()?"OMITIDO":"IMPORTADO",hash,result.created(),result.repeated(),
                result.invoiceIds().isEmpty()?"Sin facturas F1/F20 en un formato compatible":null);
        return result;
    }
}
