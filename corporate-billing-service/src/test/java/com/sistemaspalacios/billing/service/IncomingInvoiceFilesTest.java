package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.*;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.service.siigo.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncomingInvoiceFilesTest {
    @TempDir Path temporary;

    @Test
    void copiesOfficialPdfWithoutTouchingSourceAndRejectsAmbiguousNit() throws Exception {
        var repository = mock(RepositorioFactura.class);
        var folder = mock(SiigoSharedFolderReader.class);
        var metadata = mock(PdfMetadataExtractor.class);
        var service = new SiigoPdfImporterService(repository, folder, metadata);
        ReflectionTestUtils.setField(service, "localPdfPath", temporary.resolve("local").toString());
        Invoice invoice = mock(Invoice.class);
        UUID id = UUID.randomUUID();
        when(invoice.getId()).thenReturn(id);
        when(invoice.getBillingType()).thenReturn(BillingType.F1);
        when(invoice.getStatus()).thenReturn(InvoiceStatus.CONSOLIDATED);
        when(invoice.getInvoiceNumber()).thenReturn("51993");
        when(repository.findAll()).thenReturn(List.of(invoice));
        Path source = temporary.resolve("F001000000519930000.PDF");
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(source.toFile()); }
        byte[] original = Files.readAllBytes(source);
        assertTrue(service.classifyAndLinkPdf(source.toFile()).isPresent());
        var saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(invoice).setPdfPath(saved.capture());
        assertArrayEquals(original, Files.readAllBytes(Path.of(saved.getValue())));
        assertArrayEquals(original, Files.readAllBytes(source));
        when(invoice.getPdfPath()).thenReturn(saved.getValue());
        assertTrue(service.classifyAndLinkPdf(source.toFile()).isPresent());
        verify(repository, times(1)).save(invoice);

        Path unknown = temporary.resolve("unknown.pdf");
        Files.copy(source, unknown);
        when(metadata.extractMetadata(unknown.toFile())).thenReturn(new PdfMetadataExtractor.PdfExtractedMetadata(null, null, "901114119", true));
        assertTrue(service.classifyAndLinkPdf(unknown.toFile()).isEmpty(), "Un NIT no identifica una factura");
        Path wrongType = temporary.resolve("F004000000519930000.PDF");
        Files.copy(source, wrongType);
        assertTrue(service.classifyAndLinkPdf(wrongType.toFile()).isEmpty());
    }

    @Test
    void malformedFileDoesNotPreventImportingNextFile() throws Exception {
        var folder = mock(SiigoSharedFolderReader.class);
        var importer = mock(IncomingFileIntakeService.class);
        Path bad = Files.writeString(temporary.resolve("bad.json"), "invalid");
        Path good = Files.writeString(temporary.resolve("good.json"), "[]");
        when(folder.isFolderAccessible()).thenReturn(true);
        when(folder.listCarteraSpreadsheets()).thenReturn(List.of());
        when(folder.listConfirmationFlatFiles()).thenReturn(List.of(bad.toFile(), good.toFile()));
        when(importer.receive(eq("bad.json"), any(), eq("CARPETA"))).thenThrow(new IllegalArgumentException("Formato inválido"));
        when(importer.receive(eq("good.json"), any(), eq("CARPETA"))).thenReturn(new OfficialInvoiceImportService.ImportResult(List.of(UUID.randomUUID()), 1, 0));
        var result = new OfficialInvoiceFolderSyncService(importer, folder).scan();
        assertEquals(1, result.verifiedDocuments());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().contains("bad.json"));
        assertTrue(Files.exists(bad));
        assertTrue(Files.exists(good));
    }
}
