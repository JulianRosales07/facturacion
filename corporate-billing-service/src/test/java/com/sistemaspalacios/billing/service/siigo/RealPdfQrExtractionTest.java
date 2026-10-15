package com.sistemaspalacios.billing.service.siigo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService.ClientInvoiceGroup;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService.InvoiceRow;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Validación del QR real oficial de facturas PDF (ZXing)")
class RealPdfQrExtractionTest {

    private final PdfQrCodeExtractor extractor = new PdfQrCodeExtractor();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    @DisplayName("1. Extrae y decodifica el QR REAL desde el PDF oficial Siigo F001000000519930000.PDF")
    void testExtractRealQrFromOfficialSiigoPdf() {
        File officialPdf = new File("Docs/server/F001000000519930000.PDF");
        if (!officialPdf.exists()) {
            officialPdf = new File("../Docs/server/F001000000519930000.PDF");
        }
        assertTrue(officialPdf.exists(), "El archivo de prueba oficial debe existir en Docs/server/F001000000519930000.PDF");

        PdfQrCodeExtractor.ExtractedQr result = extractor.extractQr(officialPdf);

        assertNotNull(result);
        assertTrue(result.hasContent(), "El QR real debe ser detectado y decodificado exitosamente");
        assertEquals("EXTRAIDO", result.status());

        String content = result.content();
        assertNotNull(content);

        // 1. Debe contener los datos oficiales de la DIAN impresos en la factura
        assertTrue(content.contains("NumFac: FESP51993"), "Debe contener el consecutivo oficial FESP51993");
        assertTrue(content.contains("NitFac: 900163149"), "Debe contener el NIT del emisor");
        assertTrue(content.contains("DocAdq: 1088970617"), "Debe contener el documento del adquirente");
        assertTrue(content.contains("https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey="),
                "Debe contener la URL de consulta oficial de la DIAN");

        // 2. Debe extraer el CUFE oficial correspondiente a la factura
        String cufe = result.cufe();
        assertNotNull(cufe, "Debe ser capaz de aislar el CUFE del payload oficial");
        assertEquals("720377e072ca996f78d1f873a16899a51af08fc5ca705c4ebdfa9841c2320214396cd348fac2e601082dce87023b26e3",
                cufe.toLowerCase());

        // 3. Debe generar o extraer la imagen PNG del QR
        assertNotNull(result.imageBytes(), "Debe conservar los bytes PNG del código QR");
        assertTrue(result.imageBytes().length > 100, "La imagen del QR debe ser un archivo de imagen válido");
    }

    @Test
    @DisplayName("2. Un PDF sin código QR retorna estado NO_ENCONTRADO sin inventar datos ficticios")
    void testPdfWithoutQrReturnsNotFound(@TempDir Path tempDir) throws Exception {
        // Crear un PDF de prueba limpio que no contenga códigos QR
        File pdfSinQr = tempDir.resolve("factura_sin_qr.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("FACTURA DE VENTA F1-99999");
                cs.endText();
            }
            doc.save(pdfSinQr);
        }

        PdfQrCodeExtractor.ExtractedQr result = extractor.extractQr(pdfSinQr);

        assertNotNull(result);
        assertFalse(result.hasContent(), "No debe inventar contenido QR para un PDF sin QR");
        assertNull(result.content(), "El contenido debe ser null");
        assertEquals("NO_ENCONTRADO", result.status(), "El estado debe ser explícitamente NO_ENCONTRADO");
    }

    @Test
    @DisplayName("3. Un NIT con múltiples facturas: cada factura conserva su propio QR sin mezclarse")
    void testMultipleInvoicesForSameNitKeepTheirOwnQr() throws Exception {
        String nit = "900123456";
        String qrContent1 = "https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey=CUFE_FACTURA_1001";
        String qrContent2 = "https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey=CUFE_FACTURA_1002";

        InvoiceRow row1 = new InvoiceRow(
                UUID.randomUUID(),
                "1001",
                nit,
                "EMPRESA EJEMPLO S.A.S.",
                BillingType.F1,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 15),
                new BigDecimal("100000.00"),
                true,
                "CUFE_FACTURA_1001",
                qrContent1,
                "/storage/siigo-pdfs/1001/qr.png"
        );

        InvoiceRow row2 = new InvoiceRow(
                UUID.randomUUID(),
                "1002",
                nit,
                "EMPRESA EJEMPLO S.A.S.",
                BillingType.F1,
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 16),
                new BigDecimal("250000.00"),
                true,
                "CUFE_FACTURA_1002",
                qrContent2,
                "/storage/siigo-pdfs/1002/qr.png"
        );

        InvoiceRow row3SinQr = new InvoiceRow(
                UUID.randomUUID(),
                "1003",
                nit,
                "EMPRESA EJEMPLO S.A.S.",
                BillingType.F1,
                LocalDate.of(2026, 9, 3),
                LocalDate.of(2026, 9, 17),
                new BigDecimal("50000.00"),
                true,
                "CUFE_FACTURA_1003",
                null, // PDF sin QR
                null
        );

        List<ClientInvoiceGroup> groups = IncomingInvoiceQueryService.groupByNit(List.of(row1, row2, row3SinQr));

        assertEquals(1, groups.size(), "Debe existir exactamente 1 grupo para el NIT");
        ClientInvoiceGroup group = groups.get(0);
        assertEquals(nit, group.nit());
        assertEquals(3, group.invoices().size());
        assertEquals(new BigDecimal("400000.00"), group.totalServiceValue());

        // Verificación de Factura 1: conserva su propio QR
        var inv1 = group.invoices().get(0);
        assertEquals("1001", inv1.invoiceNumber());
        assertNotNull(inv1.qr(), "Factura 1 debe tener objeto QR");
        assertEquals(qrContent1, inv1.qr().content());
        assertEquals("/api/v1/facturas/F1-1001/qr.png", inv1.qr().image());
        assertNull(inv1.qr().status());

        // Verificación de Factura 2: conserva su propio QR diferente
        var inv2 = group.invoices().get(1);
        assertEquals("1002", inv2.invoiceNumber());
        assertNotNull(inv2.qr(), "Factura 2 debe tener objeto QR");
        assertEquals(qrContent2, inv2.qr().content());
        assertEquals("/api/v1/facturas/F1-1002/qr.png", inv2.qr().image());
        assertNotEquals(inv1.qr().content(), inv2.qr().content(), "El QR de la factura 1 no debe ser reutilizado en la factura 2");

        // Verificación de Factura 3: no tiene QR, debe marcar estado NO_ENCONTRADO
        var inv3 = group.invoices().get(2);
        assertEquals("1003", inv3.invoiceNumber());
        assertNotNull(inv3.qr());
        assertEquals("NO_ENCONTRADO", inv3.qr().status(), "La factura sin QR en su PDF debe marcar NO_ENCONTRADO");
        assertNull(inv3.qr().content(), "No debe tener contenido falso");

        // Verificación de estructura JSON final
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(group);
        System.out.println("JSON consolidado por NIT con QR individuales:\n" + json);

        assertTrue(json.contains("\"nit\" : \"900123456\""));
        assertTrue(json.contains(qrContent1));
        assertTrue(json.contains(qrContent2));
        assertTrue(json.contains("\"NO_ENCONTRADO\""));
    }
}
