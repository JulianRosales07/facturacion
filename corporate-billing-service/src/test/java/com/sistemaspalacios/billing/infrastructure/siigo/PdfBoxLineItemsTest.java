package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor.PdfExtractedMetadata;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor.PdfLineItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Lectura de la tabla de conceptos de la representación gráfica de Siigo. */
class PdfBoxLineItemsTest {

    private final PdfBoxMetadataExtractor extractor = new PdfBoxMetadataExtractor();

    @Test
    @DisplayName("PDF real FESP 51993: 5 conceptos que cuadran con Total Bruto, IVA y Total a Pagar")
    void readsTheRealSiigoInvoice() {
        PdfExtractedMetadata meta = extractor.extractMetadata(new File("Docs/server/F001000000519930000.PDF"));

        assertEquals("FESP 51993", meta.invoiceNumber());
        assertEquals(LocalDate.of(2026, 9, 10), meta.issueDate());
        assertEquals(LocalDate.of(2026, 9, 25), meta.dueDate());
        assertNull(meta.itemsIssue(), "El detalle debe cuadrar: " + meta.itemsIssue());
        assertTrue(meta.hasConsistentItems());

        List<PdfLineItem> items = meta.items();
        assertEquals(5, items.size());
        PdfLineItem first = items.get(0);
        assertEquals(1, first.item(), "El texto vertical de Siigo pegado delante ('-81') no altera el número de ítem");
        assertEquals("0100040000021", first.code());
        assertEquals("LA CRUZ.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026", first.description());
        assertEquals(0, first.quantity().compareTo(BigDecimal.ONE));
        assertEquals("UN", first.unit());
        assertEquals(new BigDecimal("2722500.00"), first.unitPrice());
        assertEquals(new BigDecimal("19"), first.ivaPercent());
        assertEquals(new BigDecimal("517275.00"), first.ivaValue());
        assertEquals(new BigDecimal("3239775.00"), first.total());
        assertEquals("PLAZUELAS INT.FIB.DEDI 200MB GR 12-31 AGT 2026", items.get(4).description());

        assertEquals(new BigDecimal("4785000.00"), meta.grossAmount());
        assertEquals(new BigDecimal("909150.00"), meta.ivaAmount());
        assertEquals(new BigDecimal("5694150.00"), meta.totalAmount());
    }

    @Test
    @DisplayName("PDF real F20 FEMG 287: un concepto con cantidad e importes en la misma línea")
    void readsTheRealF20Invoice() {
        PdfExtractedMetadata meta = extractor.extractMetadata(new File("Docs/archivosplanos/F020000000002870000.PDF"));

        assertEquals("FEMG 287", meta.invoiceNumber());
        assertEquals("800160682-0", meta.clientNit());
        assertEquals(LocalDate.of(2026, 9, 10), meta.issueDate());
        assertEquals(LocalDate.of(2026, 9, 25), meta.dueDate());
        assertNull(meta.itemsIssue(), "El detalle debe cuadrar: " + meta.itemsIssue());
        assertTrue(meta.hasConsistentItems());

        assertEquals(1, meta.items().size());
        PdfLineItem line = meta.items().get(0);
        assertEquals(1, line.item());
        assertEquals("0100030000013", line.code());
        assertEquals("NT.FIB.CORP.200MB GR 01-31 AGT 2026", line.description());
        assertEquals(0, line.quantity().compareTo(BigDecimal.ONE));
        assertEquals(new BigDecimal("160000.00"), line.taxableBase());
        assertEquals(new BigDecimal("30400.00"), line.ivaValue());
        assertEquals(new BigDecimal("160000.00"), meta.grossAmount());
        assertEquals(new BigDecimal("30400.00"), meta.ivaAmount());
        assertEquals(new BigDecimal("190400.00"), meta.totalAmount());
    }

    @Test
    @DisplayName("Si la suma de las líneas no coincide con el total del PDF, el detalle no se usa y se informa el motivo")
    void inconsistentDetailIsRejected() {
        String text = """
                FACTURA ELECTRÓNICA DE VENTA
                FESP 100
                1 0100040000021 SEDE A GR 01-30 SEP 2026
                1 UN 100,000.00 19 19,000.00 119,000.00
                Total Items : 1
                 Total Bruto 100,000.00
                 IVA Tarifa 19% 19,000.00
                 Total A Pagar $ 200,000.00
                """;
        PdfExtractedMetadata meta = extractor.extractFromText(text);

        assertEquals(1, meta.items().size());
        assertFalse(meta.hasConsistentItems());
        assertTrue(meta.itemsIssue().contains("Total a Pagar"), meta.itemsIssue());
    }

    @Test
    @DisplayName("Un PDF sin tabla de conceptos no inventa detalle")
    void pdfWithoutTableHasNoItems() {
        PdfExtractedMetadata meta = extractor.extractFromText("FACTURA ELECTRÓNICA DE VENTA\nFESP 7\n Total A Pagar $ 10.00");
        assertTrue(meta.items().isEmpty());
        assertFalse(meta.hasConsistentItems());
    }
}
