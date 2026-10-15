package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata extractor using Apache PDFBox 3.x to inspect text inside PDF files.
 * Extracts CUFE/CUDE hashes, client NIT, invoice number and the client header block
 * printed on the Siigo graphic representation.
 * <p>
 * Patterns are tuned against the real Siigo layout ({@code Docs/Ejemplo carpeta siggo}):
 * <pre>
 * FACTURA ELECTRÓNICA DE VENTA
 * FESP 51993
 * Cliente / Nit / Ced. / Dirección / Teléfono      (labels)
 * CRISTIAN FERNANDO BRAVO MUÑOZ                     (business name)
 * 1,088,970,617 - 6                                 (NIT - DV)
 * LA CRUZ - NUEVO PUNTO                             (address)
 * 3135963672                                        (phone)
 * ...
 * Correo crisfe8609@gmail.com
 * Expedición 2026-09-10 / Vencimiento 2026-09-25
 * Total A Pagar $ 5,694,150.00
 * </pre>
 */
@Component
public class PdfBoxMetadataExtractor implements PdfMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxMetadataExtractor.class);

    private static final Pattern CUFE_EXPLICIT_PATTERN = Pattern.compile(
            "(?i)(?:CUFE|CUDE)\\s*[:\\s]\\s*([0-9a-fA-F]{64,96})");
    private static final Pattern CUFE_HEX_PATTERN = Pattern.compile(
            "\\b([0-9a-fA-F]{64,96})\\b");

    /** Siigo layout: the document number is on the line right after the title. */
    private static final Pattern SIIGO_INVOICE_NUM_PATTERN = Pattern.compile(
            "(?iu)FACTURA\\s+ELECTR[ÓO]NICA\\s+DE\\s+VENTA\\s*\\R\\s*([A-Z]{1,5}\\s*\\d{1,10})\\s*$",
            Pattern.MULTILINE);
    private static final Pattern INVOICE_NUM_PATTERN = Pattern.compile(
            "(?i)(?:FACTURA|FACTURA ELECTRONICA DE VENTA|COMPROBANTE)\\s*(?:No\\.?|N[ÚU]MERO)?\\s*[:\\s]\\s*((?:FESP|FEMG|F1|F20)?\\s*\\d{1,10})");

    /**
     * Client block: after the "Teléfono" label come business name, NIT (with thousands
     * separators and DV), address and phone, each on its own line.
     */
    private static final Pattern CLIENT_BLOCK_PATTERN = Pattern.compile(
            "(?iu)Tel[ée]fono[ \\t]*\\R(.+)\\R[ \\t]*(\\d{1,3}(?:[.,]\\d{3})+|\\d{6,12})[ \\t]*-[ \\t]*(\\d)[ \\t]*\\R(.*)\\R[ \\t]*([+\\d][\\d \\t-]{5,})?");
    private static final Pattern NIT_PATTERN = Pattern.compile(
            "(?i)(?:NIT|C\\.C\\.|CC)\\s*[:\\s]\\s*(\\d{7,10}(?:-\\d)?)");
    private static final Pattern CLIENT_EMAIL_PATTERN = Pattern.compile(
            "(?im)^\\s*Correo\\s+([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern ISSUE_DATE_PATTERN = Pattern.compile(
            "(?iu)Expedici[óo]n\\s+(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DUE_DATE_PATTERN = Pattern.compile(
            "(?i)Vencimiento\\s+(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DUE_DATE_INSTALLMENT_PATTERN = Pattern.compile(
            "(?i)Vence\\s+el\\s+(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
            "(?i)Total\\s+A\\s+Pagar\\s*\\$?\\s*([\\d.,]+\\d)");

    // --- Tabla de conceptos (Item / Codigo / Descripcion / Cant / Und / V. Unit. / %Iva / Vr.Iva / Vr.Total) ---

    /** Inicio de una línea: número de ítem, código de producto y comienzo de la descripción. */
    private static final Pattern ITEM_START_PATTERN = Pattern.compile("(\\d{1,4})\\s+(\\d{6,20})\\s+(.*)$");
    /** Cierre de la línea: cantidad, unidad, valor unitario, % IVA, valor IVA y total con IVA. */
    private static final String ITEM_AMOUNTS =
            "(\\d+(?:\\.\\d+)?)\\s+([A-Za-z]{1,6})\\s+([\\d,]+\\.\\d{2})\\s+(\\d{1,2}(?:\\.\\d+)?)\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$";
    private static final Pattern ITEM_AMOUNTS_PATTERN = Pattern.compile("^\\s*" + ITEM_AMOUNTS);
    /** Concepto de una sola línea: la descripción y sus importes vienen juntos (p. ej. FEMG 287). */
    private static final Pattern ITEM_INLINE_PATTERN = Pattern.compile("^(.*\\S)\\s+" + ITEM_AMOUNTS);
    private static final Pattern TOTAL_ITEMS_PATTERN = Pattern.compile("(?i)Total\\s+Items\\s*:\\s*(\\d+)");
    private static final Pattern GROSS_PATTERN = Pattern.compile("(?i)Total\\s+Bruto\\s+([\\d,]+\\.\\d{2})");
    /** Puede haber una línea por tarifa ("IVA Tarifa 19%", "IVA Tarifa 5%"): se suman. */
    private static final Pattern IVA_PATTERN = Pattern.compile("(?i)IVA\\s+Tarifa\\s+\\d+(?:[.,]\\d+)?\\s*%\\s+([\\d,]+\\.\\d{2})");

    @Override
    public PdfExtractedMetadata extractMetadata(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists() || !pdfFile.isFile()) {
            return PdfExtractedMetadata.empty();
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return extractFromText(text);
        } catch (Exception e) {
            log.error("Error extracting text with PDFBox from file {}: {}", pdfFile.getName(), e.getMessage());
            return PdfExtractedMetadata.empty();
        }
    }

    @Override
    public PdfExtractedMetadata extractMetadata(InputStream inputStream) {
        if (inputStream == null) {
            return PdfExtractedMetadata.empty();
        }

        try {
            byte[] bytes = inputStream.readAllBytes();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                return extractFromText(text);
            }
        } catch (Exception e) {
            log.error("Error extracting text with PDFBox from stream: {}", e.getMessage());
            return PdfExtractedMetadata.empty();
        }
    }

    /** Visible for testing: parses already-extracted PDF text. */
    PdfExtractedMetadata extractFromText(String text) {
        if (text == null || text.isBlank()) {
            return PdfExtractedMetadata.empty();
        }

        String cufe = firstGroup(CUFE_EXPLICIT_PATTERN, text);
        if (cufe == null) {
            cufe = firstGroup(CUFE_HEX_PATTERN, text);
        }

        String invoiceNum = firstGroup(SIIGO_INVOICE_NUM_PATTERN, text);
        if (invoiceNum == null) {
            invoiceNum = firstGroup(INVOICE_NUM_PATTERN, text);
        }
        if (invoiceNum != null) {
            invoiceNum = invoiceNum.replaceAll("\\s+", " ");
        }

        String nit = null;
        String businessName = null;
        String address = null;
        String phone = null;
        Matcher client = CLIENT_BLOCK_PATTERN.matcher(text);
        if (client.find()) {
            businessName = blankToNull(client.group(1));
            nit = client.group(2).replaceAll("[.,]", "") + "-" + client.group(3);
            address = blankToNull(client.group(4));
            phone = client.group(5) != null ? blankToNull(client.group(5).replaceAll("\\s+", "")) : null;
        } else {
            nit = firstGroup(NIT_PATTERN, text);
        }

        String email = firstGroup(CLIENT_EMAIL_PATTERN, text);
        LocalDate issueDate = parseDate(firstGroup(ISSUE_DATE_PATTERN, text));
        LocalDate dueDate = parseDate(firstGroup(DUE_DATE_PATTERN, text));
        if (dueDate == null) {
            dueDate = parseDate(firstGroup(DUE_DATE_INSTALLMENT_PATTERN, text));
        }
        BigDecimal total = parseAmount(firstGroup(TOTAL_PATTERN, text));

        List<PdfLineItem> items = parseLineItems(text);
        BigDecimal gross = parseAmount(firstGroup(GROSS_PATTERN, text));
        BigDecimal iva = sumAll(IVA_PATTERN, text);
        String itemsIssue = validateItems(items, firstGroup(TOTAL_ITEMS_PATTERN, text), gross, iva, total);

        boolean found = cufe != null || nit != null || invoiceNum != null;
        return new PdfExtractedMetadata(invoiceNum, cufe, nit, found,
                businessName, address, phone, email, issueDate, dueDate, total, items, gross, iva, itemsIssue);
    }

    /**
     * Lee la tabla de conceptos. Cada concepto empieza con "nº código descripción…", puede
     * seguir en varias líneas, y cierra con la línea de importes (o los trae en la misma línea). El número de ítem debe ser
     * consecutivo: así se tolera el texto vertical de Siigo que a veces queda pegado delante
     * del primer ítem ("-81 0100…" es el ítem 1) sin confundir otras líneas con conceptos.
     */
    static List<PdfLineItem> parseLineItems(String text) {
        List<PdfLineItem> items = new ArrayList<>();
        String[] lines = text.split("\\R");
        int expected = 1;
        for (int i = 0; i < lines.length; i++) {
            if (TOTAL_ITEMS_PATTERN.matcher(lines[i]).find()) break;
            Matcher start = ITEM_START_PATTERN.matcher(lines[i]);
            if (!start.find() || !start.group(1).endsWith(String.valueOf(expected))) continue;

            StringBuilder description = new StringBuilder(start.group(3).trim());
            Matcher amounts = null;
            int offset = 0;
            int j = i;
            Matcher inline = ITEM_INLINE_PATTERN.matcher(description);
            if (inline.find()) {
                description = new StringBuilder(inline.group(1));
                amounts = inline;
                offset = 1;
            } else {
                for (j = i + 1; j < lines.length && j <= i + 6; j++) {
                    Matcher candidate = ITEM_AMOUNTS_PATTERN.matcher(lines[j]);
                    if (candidate.find()) { amounts = candidate; break; }
                    description.append(' ').append(lines[j].trim());
                }
            }
            if (amounts == null) continue;

            items.add(new PdfLineItem(expected, start.group(2), description.toString().replaceAll("\\s+", " ").trim(),
                    new BigDecimal(amounts.group(offset + 1)), amounts.group(offset + 2).toUpperCase(java.util.Locale.ROOT),
                    parseAmount(amounts.group(offset + 3)), new BigDecimal(amounts.group(offset + 4)),
                    parseAmount(amounts.group(offset + 5)), parseAmount(amounts.group(offset + 6))));
            expected++;
            i = j;
        }
        return items;
    }

    /**
     * El detalle solo se usa si cuadra con lo que el propio PDF declara: número de ítems, total
     * bruto (suma de bases), IVA y total a pagar. Devuelve el motivo si no cuadra; null si sí.
     */
    static String validateItems(List<PdfLineItem> items, String declaredCount, BigDecimal gross, BigDecimal iva, BigDecimal total) {
        if (items.isEmpty()) {
            return "El PDF no tiene una tabla de conceptos legible";
        }
        if (declaredCount != null && Integer.parseInt(declaredCount) != items.size()) {
            return "El PDF declara " + declaredCount + " ítems y se leyeron " + items.size();
        }
        BigDecimal sumBase = BigDecimal.ZERO, sumIva = BigDecimal.ZERO, sumTotal = BigDecimal.ZERO;
        for (PdfLineItem item : items) {
            if (item.taxableBase().add(item.ivaValue()).compareTo(item.total()) != 0) {
                return "El ítem " + item.item() + " no cuadra: base + IVA ≠ total de la línea";
            }
            sumBase = sumBase.add(item.taxableBase());
            sumIva = sumIva.add(item.ivaValue());
            sumTotal = sumTotal.add(item.total());
        }
        if (gross != null && sumBase.compareTo(gross) != 0) {
            return "La suma de bases (" + sumBase.toPlainString() + ") no coincide con el Total Bruto (" + gross.toPlainString() + ")";
        }
        if (iva != null && sumIva.compareTo(iva) != 0) {
            return "La suma de IVA (" + sumIva.toPlainString() + ") no coincide con el IVA del PDF (" + iva.toPlainString() + ")";
        }
        if (total == null || sumTotal.compareTo(total) != 0) {
            return "La suma de las líneas (" + sumTotal.toPlainString() + ") no coincide con el Total a Pagar ("
                    + (total != null ? total.toPlainString() : "no leído") + ")";
        }
        return null;
    }

    private static BigDecimal sumAll(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        BigDecimal sum = null;
        while (m.find()) {
            BigDecimal value = parseAmount(m.group(1));
            if (value != null) sum = sum == null ? value : sum.add(value);
        }
        return sum;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Siigo prints amounts as {@code 5,694,150.00}: comma thousands, dot decimals. */
    private static BigDecimal parseAmount(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
