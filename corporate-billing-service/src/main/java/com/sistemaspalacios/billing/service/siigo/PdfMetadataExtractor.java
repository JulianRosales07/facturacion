package com.sistemaspalacios.billing.service.siigo;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for extracting metadata (CUFE, NIT, Invoice Number) from PDF documents.
 * <p>
 * Besides the identity fields used to link a PDF with its invoice, it also exposes the
 * client header data printed on the Siigo graphic representation (business name, address,
 * phone, email, issue/due dates and total). Those fields are only used to <b>complete</b>
 * data missing from the official data file; they never replace it.
 */
public interface PdfMetadataExtractor {

    /**
     * Extracts CUFE, NIT, and invoice number from a PDF file.
     *
     * @param pdfFile The PDF file
     * @return Extracted metadata record
     */
    PdfExtractedMetadata extractMetadata(File pdfFile);

    /**
     * Extracts metadata from an input stream.
     *
     * @param inputStream PDF input stream
     * @return Extracted metadata record
     */
    PdfExtractedMetadata extractMetadata(InputStream inputStream);

    record PdfExtractedMetadata(
            String invoiceNumber,
            String cufe,
            String clientNit,
            boolean successfullyExtracted,
            String clientBusinessName,
            String clientAddress,
            String clientPhone,
            String clientEmail,
            LocalDate issueDate,
            LocalDate dueDate,
            BigDecimal totalAmount,
            // Detalle impreso en la representación gráfica de Siigo.
            List<PdfLineItem> items,
            BigDecimal grossAmount,
            BigDecimal ivaAmount,
            /** Motivo por el que el detalle no se puede usar (no cuadra con los totales); null si es consistente. */
            String itemsIssue
    ) {
        public PdfExtractedMetadata {
            items = items != null ? List.copyOf(items) : List.of();
        }

        public PdfExtractedMetadata(String invoiceNumber, String cufe, String clientNit, boolean successfullyExtracted,
                                    String clientBusinessName, String clientAddress, String clientPhone, String clientEmail,
                                    LocalDate issueDate, LocalDate dueDate, BigDecimal totalAmount) {
            this(invoiceNumber, cufe, clientNit, successfullyExtracted, clientBusinessName, clientAddress, clientPhone,
                    clientEmail, issueDate, dueDate, totalAmount, List.of(), null, null, null);
        }

        /** Identity-only metadata (kept for backwards compatibility). */
        public PdfExtractedMetadata(String invoiceNumber, String cufe, String clientNit, boolean successfullyExtracted) {
            this(invoiceNumber, cufe, clientNit, successfullyExtracted, null, null, null, null, null, null, null);
        }

        public static PdfExtractedMetadata empty() {
            return new PdfExtractedMetadata(null, null, null, false);
        }

        /** El detalle existe y cuadra con el total bruto, el IVA y el total a pagar del propio PDF. */
        public boolean hasConsistentItems() {
            return !items.isEmpty() && itemsIssue == null;
        }
    }

    /**
     * Una línea de la tabla "Item / Codigo / Descripcion / Cant / Und / V. Unit. / %Iva / Vr.Iva /
     * Vr.Total incluido Iva" de la factura de Siigo. Importes con precisión decimal exacta.
     */
    record PdfLineItem(int item, String code, String description, BigDecimal quantity, String unit,
                       BigDecimal unitPrice, BigDecimal ivaPercent, BigDecimal ivaValue, BigDecimal total) {
        /** Base gravable de la línea: valor unitario × cantidad. */
        public BigDecimal taxableBase() {
            return unitPrice.multiply(quantity).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
