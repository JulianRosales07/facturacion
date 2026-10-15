package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.infrastructure.siigo.PdfBoxMetadataExtractor;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SiigoPdfClassifierTest {

    private final PdfBoxMetadataExtractor pdfBoxExtractor = new PdfBoxMetadataExtractor();

    @Test
    @DisplayName("Nivel 1: Regex sobre convención de nombres Siigo decodifica prefijo, comprobante y consecutivo")
    void testSiigoFilenameConventionRegex() {
        Pattern pattern = Pattern.compile("^F(?<typeCode>\\d{3})(?<consecutivo>\\d+?)0000\\.pdf$", Pattern.CASE_INSENSITIVE);

        // Caso F1: F001000000519930000.PDF -> Tipo 001 (F1), consecutivo 51993
        Matcher m1 = pattern.matcher("F001000000519930000.PDF");
        assertTrue(m1.matches());
        assertEquals("001", m1.group("typeCode"));
        assertEquals("51993", m1.group("consecutivo").replaceFirst("^0+(?!$)", ""));

        // Caso F20: F020000000002870000.PDF -> Tipo 020 (F20), consecutivo 287
        Matcher m2 = pattern.matcher("F020000000002870000.PDF");
        assertTrue(m2.matches());
        assertEquals("020", m2.group("typeCode"));
        assertEquals("287", m2.group("consecutivo").replaceFirst("^0+(?!$)", ""));

        // Caso nombre no estándar
        Matcher m3 = pattern.matcher("FACTURA_ESPECIAL_999.PDF");
        assertFalse(m3.matches());
    }

    @Test
    @DisplayName("Nivel 2: Apache PDFBox extrae CUFE, NIT y número de factura desde documento real o de prueba")
    void testPdfBoxExtractionFallback() {
        File pdfReal = new File("factura_FESP51987_oficial.pdf");
        if (pdfReal.exists()) {
            PdfMetadataExtractor.PdfExtractedMetadata meta = pdfBoxExtractor.extractMetadata(pdfReal);
            assertNotNull(meta);
            assertTrue(meta.successfullyExtracted() || meta.invoiceNumber() != null || meta.cufe() != null);
        }
    }

    @Test
    @DisplayName("Extrae metadatos ricos y cliente desde PDF oficial F1 (FESP 51993)")
    void testPdfBoxExtractRichMetadataF1() {
        File pdf = new File("Docs/Ejemplo carpeta siggo/F001000000519930000.PDF");
        if (pdf.exists()) {
            PdfMetadataExtractor.PdfExtractedMetadata meta = pdfBoxExtractor.extractMetadata(pdf);
            assertNotNull(meta);
            assertTrue(meta.successfullyExtracted());
            assertEquals("FESP 51993", meta.invoiceNumber());
            assertEquals("1088970617-6", meta.clientNit());
            assertEquals("CRISTIAN FERNANDO BRAVO MUÑOZ", meta.clientBusinessName());
            assertEquals("LA CRUZ - NUEVO PUNTO", meta.clientAddress());
            assertEquals("3135963672", meta.clientPhone());
            assertEquals("crisfe8609@gmail.com", meta.clientEmail());
            assertNotNull(meta.dueDate());
            assertEquals("2026-09-25", meta.dueDate().toString());
            assertNotNull(meta.totalAmount());
            assertEquals(new java.math.BigDecimal("5694150.00"), meta.totalAmount());
            assertTrue(meta.cufe() != null && meta.cufe().startsWith("720377e0"));
        }
    }

    @Test
    @DisplayName("Extrae metadatos ricos y cliente desde PDF oficial F20 (FEMG 287)")
    void testPdfBoxExtractRichMetadataF20() {
        File pdf = new File("Docs/Ejemplo carpeta siggo/F020000000002870000.PDF");
        if (pdf.exists()) {
            PdfMetadataExtractor.PdfExtractedMetadata meta = pdfBoxExtractor.extractMetadata(pdf);
            assertNotNull(meta);
            assertTrue(meta.successfullyExtracted());
            assertEquals("FEMG 287", meta.invoiceNumber());
            assertEquals("800160682-0", meta.clientNit());
            assertEquals("FRESCOS DE COLOMBIA COLFRESH COFFEE EXPORTADORES S.A.S", meta.clientBusinessName());
            assertEquals("colfreshcoffee@colfreshcoffee.com", meta.clientEmail());
            assertEquals(new java.math.BigDecimal("190400.00"), meta.totalAmount());
            assertTrue(meta.cufe() != null && meta.cufe().startsWith("d5703d40"));
        }
    }
}
