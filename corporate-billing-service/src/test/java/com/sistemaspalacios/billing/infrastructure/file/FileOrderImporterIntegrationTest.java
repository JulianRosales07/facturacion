package com.sistemaspalacios.billing.infrastructure.file;

import com.sistemaspalacios.billing.domain.model.FileStatus;
import com.sistemaspalacios.billing.domain.model.ImportedFile;
import com.sistemaspalacios.billing.domain.model.ImportedFileLine;
import com.sistemaspalacios.billing.domain.model.ImportedLineStatus;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.repository.RepositorioArchivoImportado;
import com.sistemaspalacios.billing.repository.RepositorioArchivoImportadoLinea;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class FileOrderImporterIntegrationTest {

    @Autowired
    private FileOrderImporter fileOrderImporter;

    @Autowired
    private RepositorioArchivoImportado repositorioArchivoImportado;

    @Autowired
    private RepositorioArchivoImportadoLinea repositorioArchivoImportadoLinea;

    @Autowired
    private RepositorioSede repositorioSede;

    @Test
    @DisplayName("HU-03, HU-27: Ingesta de archivo con trazabilidad y errores por línea que no bloquean el lote")
    void testNonBlockingFileImportWithTraceability() {
        String periodCode = "2026-08";
        String fileName = "corte_ordenes_2026_08_batch1.jsonl";

        String payload = """
            {"origin":"SPE","nit":"900333222","businessName":"Empresa Lote Uno","externalOrderCode":"ORD-F01","siteName":"Sede Central","monthlyValue":400000.00,"certificationDate":"2026-08-01"}
            {"origin":"SPE","nit":"900333222","businessName":"Empresa Lote Uno","externalOrderCode":"ORD-F02","siteName":"Sede Norte","monthlyValue":250000.00,"certificationDate":"2026-08-05"}
            {"invalidJsonLine": true, "missingNit": "no_nit_provided"}
            {"origin":"SPE","nit":"900333222","businessName":"Empresa Lote Uno","externalOrderCode":"ORD-F03","siteName":"Sede Sur","monthlyValue":300000.00,"certificationDate":"2026-08-10"}
            """;

        ImportedFile resultFile = fileOrderImporter.importFromStringPayload(
                fileName,
                OrderOrigin.SPE,
                periodCode,
                payload
        );

        assertNotNull(resultFile.getId());
        assertEquals(fileName, resultFile.getFileName());
        assertEquals(OrderOrigin.SPE, resultFile.getOrigin());
        assertEquals(4, resultFile.getTotalRecords());
        assertEquals(1, resultFile.getErrorRecords());
        assertEquals(FileStatus.PROCESADO, resultFile.getStatus());

        // Check line items traceability in database
        List<ImportedFileLine> lines = repositorioArchivoImportadoLinea
                .findByImportedFileIdOrderByLineNumberAsc(resultFile.getId());

        assertEquals(4, lines.size());

        // Line 1: Success
        assertEquals(1, lines.get(0).getLineNumber());
        assertEquals(ImportedLineStatus.PROCESADA, lines.get(0).getStatus());
        assertNotNull(lines.get(0).getSiteId());
        assertNull(lines.get(0).getErrorMessage());

        // Line 2: Success
        assertEquals(2, lines.get(1).getLineNumber());
        assertEquals(ImportedLineStatus.PROCESADA, lines.get(1).getStatus());
        assertNotNull(lines.get(1).getSiteId());

        // Line 3: Error (non-blocking)
        assertEquals(3, lines.get(2).getLineNumber());
        assertEquals(ImportedLineStatus.ERROR, lines.get(2).getStatus());
        assertNull(lines.get(2).getSiteId());
        assertNotNull(lines.get(2).getErrorMessage());
        assertFalse(lines.get(2).getErrorMessage().isBlank());

        // Line 4: Success despite Line 3 failing!
        assertEquals(4, lines.get(3).getLineNumber());
        assertEquals(ImportedLineStatus.PROCESADA, lines.get(3).getStatus());
        assertNotNull(lines.get(3).getSiteId());

        // Verify valid sites are actually in the database
        Optional<SubscriptionSite> site1 = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-F01", OrderOrigin.SPE);
        Optional<SubscriptionSite> site2 = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-F02", OrderOrigin.SPE);
        Optional<SubscriptionSite> site3 = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-F03", OrderOrigin.SPE);

        assertTrue(site1.isPresent());
        assertTrue(site2.isPresent());
        assertTrue(site3.isPresent());
    }
}
