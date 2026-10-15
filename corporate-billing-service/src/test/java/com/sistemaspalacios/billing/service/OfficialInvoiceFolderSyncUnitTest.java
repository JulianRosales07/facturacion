package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.ReceivedPdf;
import com.sistemaspalacios.billing.repository.ReceivedPdfRepository;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor;
import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import com.sistemaspalacios.billing.web.controller.GlobalExceptionHandler;
import com.sistemaspalacios.billing.web.controller.InvoiceController;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfficialInvoiceFolderSyncUnitTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Tarea 1: Carpeta local de PDF no escribible lanza IllegalStateException con mensaje claro")
    void nonWritableLocalFolderFailsCycleWithClearMessage() throws IOException {
        var repoFactura = mock(RepositorioFactura.class);
        var folderReader = mock(SiigoSharedFolderReader.class);
        var metadata = mock(PdfMetadataExtractor.class);

        var service = new SiigoPdfImporterService(repoFactura, folderReader, metadata);

        // Apuntar a una ruta inválida o archivo ordinario donde no se puede crear directorio
        Path nonWritable = tempDir.resolve("archivo_bloqueante");
        Files.writeString(nonWritable, "bloqueo");
        Path invalidDir = nonWritable.resolve("subcarpeta_imposible");

        ReflectionTestUtils.setField(service, "localPdfPath", invalidDir.toString());

        var ex = assertThrows(IllegalStateException.class, service::processSharedFolderPdfs);
        assertTrue(ex.getMessage().startsWith("Carpeta local de PDF no escribible:"),
                "El mensaje debe indicar que la carpeta no es escribible: " + ex.getMessage());
    }

    @Test
    @DisplayName("Tarea 2: PDF pendiente se asocia usando la copia local aunque el original ya no exista en la carpeta")
    void pendingPdfIsAssociatedFromLocalCopyWithoutOriginal() throws Exception {
        var repoFactura = mock(RepositorioFactura.class);
        var folderReader = mock(SiigoSharedFolderReader.class);
        var metadata = mock(PdfMetadataExtractor.class);
        var receivedRepo = mock(ReceivedPdfRepository.class);

        var service = new SiigoPdfImporterService(
                repoFactura, folderReader, metadata, null, null, receivedRepo, null
        );

        Path localDir = tempDir.resolve("local-copies");
        Path pendingDir = localDir.resolve("_pendientes");
        Files.createDirectories(pendingDir);
        ReflectionTestUtils.setField(service, "localPdfPath", localDir.toString());

        // Crear PDF en _pendientes
        String hash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        Path pendingPdf = pendingDir.resolve(hash + ".pdf");
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pendingPdf.toFile());
        }

        String originalName = "F001000000519930000.PDF";
        ReceivedPdf pending = new ReceivedPdf(
                originalName, hash, Files.size(pendingPdf), java.time.Instant.now(),
                pendingPdf.toString(), ReceivedPdf.STATUS_PENDING, null, "Sin factura previa"
        );

        when(receivedRepo.findByStatus(ReceivedPdf.STATUS_PENDING)).thenReturn(List.of(pending));

        Invoice invoice = mock(Invoice.class);
        UUID invoiceId = UUID.randomUUID();
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getInvoiceNumber()).thenReturn("51993");
        when(invoice.getBillingType()).thenReturn(BillingType.F1);
        when(repoFactura.findByExternalDocumentKey("F1:51993")).thenReturn(Optional.of(invoice));

        List<SiigoPdfImporterService.PdfMatchResult> matched = new ArrayList<>();
        service.reprocessPendingPdfs(matched);

        assertEquals(1, matched.size());
        assertEquals("REINTENTO_PENDIENTE", matched.getFirst().matchedBy());
        assertEquals(ReceivedPdf.STATUS_ASSOCIATED, pending.getStatus());
        assertEquals(invoiceId, pending.getInvoiceId());
        // El PDF original no existía en folderReader y la copia en _pendientes debió limpiarse
        assertFalse(Files.exists(pendingPdf), "La copia temporal en _pendientes debe limpiarse tras asociarse");
    }

    @Test
    @DisplayName("Tarea 4: Archivo de datos ya procesado se salta por hash sin parsear")
    void dataFileAlreadyProcessedIsSkippedByHash() throws Exception {
        var intake = mock(IncomingFileIntakeService.class);
        var folder = mock(SiigoSharedFolderReader.class);
        var audit = mock(IncomingFileAuditService.class);

        var service = new OfficialInvoiceFolderSyncService(intake, folder, audit, false);

        Path testFile = tempDir.resolve("archivo.xlsx");
        Files.writeString(testFile, "contenido_ficticio");

        when(folder.isFolderAccessible()).thenReturn(true);
        when(folder.listCarteraSpreadsheets()).thenReturn(List.of(testFile.toFile()));
        when(folder.listConfirmationFlatFiles()).thenReturn(List.of());
        when(audit.isAlreadyProcessed(any())).thenReturn(true);

        OfficialInvoiceFolderSyncService.ScanResult result = service.scan();

        assertEquals(1, result.skippedFiles());
        assertEquals(0, result.verifiedDocuments());
        verify(intake, never()).receive(any(), any(), any());
    }

    @Test
    @DisplayName("Tarea 6: Descarga de PDF con archivo ilegible responde con error distinguible (500) y código específico")
    void downloadWithUnreadableFileReturnsInternalServerErrorDistinguishableFromNotFound() throws Exception {
        var billingService = mock(BillingService.class);
        var creditNoteService = mock(com.sistemaspalacios.billing.service.CreditNoteService.class);

        var controller = new InvoiceController(billingService, creditNoteService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UUID id = UUID.randomUUID();
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(id);
        when(invoice.getInvoiceNumber()).thenReturn("51993");
        when(invoice.getBillingType()).thenReturn(BillingType.F1);
        when(invoice.getPdfPath()).thenReturn("/ruta/inexistente/en/disco.pdf");

        when(billingService.getInvoiceById(id)).thenReturn(invoice);

        mockMvc.perform(get("/api/v1/facturas/" + id + "/pdf"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("PDF_ALMACENAMIENTO_ILEGIBLE"));
    }
}
