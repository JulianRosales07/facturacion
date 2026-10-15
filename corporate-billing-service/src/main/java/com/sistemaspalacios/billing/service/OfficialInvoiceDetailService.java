package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor.PdfExtractedMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completa los documentos que Siigo ya emitió con el detalle impreso en su PDF oficial. No
 * genera ni recalcula facturas: interpreta lo que Siigo produjo.
 * <p>
 * Reglas entre fuentes:
 * <ul>
 *   <li><b>Conceptos, base e IVA</b>: del PDF (o del Excel de movimiento contable), siempre que
 *       el detalle cuadre con el Total Bruto, el IVA y el Total a Pagar del propio PDF.</li>
 *   <li><b>Saldo pendiente</b>: del corte de cartera. Si el documento solo tenía el concepto
 *       genérico creado desde la cartera, el valor de esa fila era un saldo: se conserva como
 *       saldo de Siigo y el total pasa a ser el del PDF.</li>
 *   <li><b>Detalle ya cargado</b> (Excel de movimiento o un PDF anterior): nunca se
 *       sobrescribe. Si el PDF dice otra cosa se registra el conflicto para conciliación.</li>
 *   <li><b>Documento con pagos</b> (pagado, abonado o con un pago en curso): no se toca. Su
 *       pago y sus retenciones ya se registraron sobre el detalle anterior; cambiarlo dejaría
 *       el registro financiero incoherente. Se deja constancia para conciliación.</li>
 *   <li><b>Periodo</b>: el del servicio, tomado de los rangos "GR dd-dd MMM aaaa" de los
 *       conceptos, cuando todos caen en el mismo mes; si no, se mantiene el mes de emisión.</li>
 * </ul>
 * Es idempotente: aplicar el mismo PDF otra vez no cambia nada.
 */
@Service
@Transactional
public class OfficialInvoiceDetailService {

    private static final Logger log = LoggerFactory.getLogger(OfficialInvoiceDetailService.class);
    static final String AUDIT_SOURCE = "PDF_DETALLE";

    public enum Outcome { APLICADO, YA_CONSISTENTE, CONFLICTO, CON_PAGOS, SIN_DETALLE, NO_APLICA }

    public record Result(Outcome outcome, String detail) {}

    private final RepositorioFactura repositorioFactura;
    private final RepositorioPeriodo repositorioPeriodo;
    private IncomingFileAuditService auditService;
    private PdfMetadataExtractor pdfMetadataExtractor;
    private RepositorioPago repositorioPago;

    public OfficialInvoiceDetailService(RepositorioFactura repositorioFactura, RepositorioPeriodo repositorioPeriodo) {
        this.repositorioFactura = repositorioFactura;
        this.repositorioPeriodo = repositorioPeriodo;
    }

    @Autowired(required = false)
    void setCollaborators(IncomingFileAuditService auditService, PdfMetadataExtractor pdfMetadataExtractor) {
        this.auditService = auditService;
        this.pdfMetadataExtractor = pdfMetadataExtractor;
    }

    @Autowired(required = false)
    void setRepositorioPago(RepositorioPago repositorioPago) {
        this.repositorioPago = repositorioPago;
    }

    /** Aplica el detalle del PDF a su documento de Siigo y deja constancia del resultado. */
    public Result applyPdfDetail(Invoice invoice, PdfExtractedMetadata meta, String sourceName) {
        Result result = decide(invoice, meta);
        if (result.outcome() == Outcome.APLICADO) {
            repositorioFactura.saveAndFlush(invoice);
        }
        if (result.outcome() != Outcome.NO_APLICA) {
            audit(invoice, sourceName, result);
        }
        log.info("Detalle del PDF {} para {}: {} — {}", sourceName, invoice.getInvoiceNumber(), result.outcome(), result.detail());
        return result;
    }

    private Result decide(Invoice invoice, PdfExtractedMetadata meta) {
        if (!invoice.isExternalDocument() || meta == null) {
            return new Result(Outcome.NO_APLICA, "No es un documento de Siigo");
        }
        if (!meta.hasConsistentItems()) {
            return new Result(Outcome.SIN_DETALLE, meta.itemsIssue() != null ? meta.itemsIssue() : "El PDF no trae detalle de conceptos");
        }

        Money pdfTotal = Money.of(meta.totalAmount());
        Money pdfGross = Money.of(meta.grossAmount() != null ? meta.grossAmount() : sumBases(meta));
        Money pdfIva = Money.of(meta.ivaAmount() != null ? meta.ivaAmount() : BigDecimal.ZERO);

        if (!invoice.hasCarteraPlaceholderDetail()) {
            boolean same = invoice.getTotalAmount().equals(pdfTotal) && invoice.getSubtotalAmount().equals(pdfGross)
                    && invoice.getIvaAmount().equals(pdfIva);
            if (same) {
                return new Result(Outcome.YA_CONSISTENTE, "El detalle registrado coincide con el PDF");
            }
            return new Result(Outcome.CONFLICTO, String.format(
                    "El registro (subtotal %s, IVA %s, total %s) no coincide con el PDF (subtotal %s, IVA %s, total %s). "
                            + "Se conserva el registro; requiere conciliación.",
                    plain(invoice.getSubtotalAmount()), plain(invoice.getIvaAmount()), plain(invoice.getTotalAmount()),
                    plain(pdfGross), plain(pdfIva), plain(pdfTotal)));
        }

        String paymentBlocker = paymentBlocker(invoice);
        if (paymentBlocker != null) {
            return new Result(Outcome.CON_PAGOS, String.format(
                    "El documento %s; no se modifica. El PDF trae subtotal %s, IVA %s, total %s y el registro tiene subtotal %s, "
                            + "IVA %s: las retenciones de ese pago se calcularon sobre el registro. Requiere conciliación.",
                    paymentBlocker, plain(pdfGross), plain(pdfIva), plain(pdfTotal),
                    plain(invoice.getSubtotalAmount()), plain(invoice.getIvaAmount())));
        }

        // Concepto genérico del corte de cartera → detalle oficial del PDF.
        Money carteraValue = invoice.getTotalAmount();
        List<InvoiceItem> official = new ArrayList<>();
        for (var line : meta.items()) {
            official.add(InvoiceItem.fromOfficialAmounts(invoice.getId(), line.code(), line.description(), line.quantity(),
                    Money.of(line.taxableBase()), Money.of(line.ivaValue())));
        }
        invoice.applyOfficialDetail(official);

        StringBuilder detail = new StringBuilder(String.format("%d conceptos del PDF; subtotal %s, IVA %s, total %s",
                official.size(), plain(pdfGross), plain(pdfIva), plain(pdfTotal)));
        if (!carteraValue.equals(pdfTotal) && !invoice.hasSiigoBalance()) {
            // El valor de la fila de cartera era el saldo pendiente, no el total del documento.
            Instant cut = invoice.getCreatedAt() != null ? invoice.getCreatedAt() : Instant.now();
            invoice.applySiigoBalance(carteraValue, cut, null);
            detail.append(". El valor del corte de cartera (").append(plain(carteraValue)).append(") se conserva como saldo pendiente");
        }
        servicePeriodOf(official).ifPresent(month -> {
            BillingPeriod period = findOrCreatePeriod(month);
            if (!period.getId().equals(invoice.getPeriodId())) {
                invoice.reassignServicePeriod(period.getId());
                detail.append(". Periodo del servicio: ").append(month);
            }
        });
        return new Result(Outcome.APLICADO, detail.toString());
    }

    /**
     * Reintento seguro: vuelve a leer los PDF ya guardados de los documentos de Siigo que
     * siguen con el concepto genérico de cartera. Idempotente.
     */
    public Map<Outcome, Integer> reapplyStoredPdfs() {
        Map<Outcome, Integer> counts = new java.util.EnumMap<>(Outcome.class);
        if (pdfMetadataExtractor == null) {
            return counts;
        }
        for (Invoice invoice : repositorioFactura.findAll()) {
            if (!invoice.hasCarteraPlaceholderDetail() || invoice.getPdfPath() == null || invoice.getPdfPath().isBlank()) {
                continue;
            }
            File pdf = new File(invoice.getPdfPath());
            if (!pdf.isFile()) {
                continue;
            }
            Result result = applyPdfDetail(invoice, pdfMetadataExtractor.extractMetadata(pdf), pdf.getName());
            counts.merge(result.outcome(), 1, Integer::sum);
        }
        return counts;
    }

    private String paymentBlocker(Invoice invoice) {
        if (invoice.getStatus() == InvoiceStatus.PAID) return "está pagado";
        if (repositorioPago == null) return null;
        if (!repositorioPago.findAppliedPaymentsByInvoiceId(invoice.getId()).isEmpty()) return "tiene pagos aplicados";
        if (!repositorioPago.findPendingPaymentsByInvoiceIds(List.of(invoice.getId())).isEmpty()) return "tiene un pago en curso en la pasarela";
        return null;
    }

    /** "… GR 01-31 AGT 2026" o mención de mes/año ("OCTUBRE 2026", "2026-10") en las líneas del mismo mes → ese mes. */
    static Optional<YearMonth> servicePeriodOf(List<InvoiceItem> items) {
        if (items == null || items.isEmpty()) return Optional.empty();
        Set<YearMonth> months = new HashSet<>();
        for (InvoiceItem item : items) {
            Optional<YearMonth> month = monthInDescription(item.getDescription());
            month.ifPresent(months::add);
        }
        return months.size() == 1 ? months.stream().findFirst() : Optional.empty();
    }

    private static final Pattern RANGE = Pattern.compile("GR\\s+\\d{1,2}-\\d{1,2}\\s+([A-Z]{3})\\s+(\\d{4})");
    private static final Pattern MONTH_NAME_YEAR = Pattern.compile("(?:PERIODO|SERVICIO)?\\s*\\b(ENERO|FEBRERO|MARZO|ABRIL|MAYO|JUNIO|JULIO|AGOSTO|SEPTIEMBRE|SETIEMBRE|OCTUBRE|NOVIEMBRE|DICIEMBRE|ENE|FEB|MAR|ABR|MAY|JUN|JUL|AGO|AGT|SEP|SET|OCT|NOV|DIC)\\b(?:\\s+DE)?\\s+(\\d{4})");
    private static final Pattern YEAR_MONTH_ISO = Pattern.compile("\\b(\\d{4})[-/](0[1-9]|1[0-2])\\b");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("ENE", 1), Map.entry("ENERO", 1),
            Map.entry("FEB", 2), Map.entry("FEBRERO", 2),
            Map.entry("MAR", 3), Map.entry("MARZO", 3),
            Map.entry("ABR", 4), Map.entry("ABRIL", 4),
            Map.entry("MAY", 5), Map.entry("MAYO", 5),
            Map.entry("JUN", 6), Map.entry("JUNIO", 6),
            Map.entry("JUL", 7), Map.entry("JULIO", 7),
            Map.entry("AGT", 8), Map.entry("AGO", 8), Map.entry("AGOSTO", 8),
            Map.entry("SEP", 9), Map.entry("SET", 9), Map.entry("SEPTIEMBRE", 9), Map.entry("SETIEMBRE", 9),
            Map.entry("OCT", 10), Map.entry("OCTUBRE", 10),
            Map.entry("NOV", 11), Map.entry("NOVIEMBRE", 11),
            Map.entry("DIC", 12), Map.entry("DICIEMBRE", 12));

    static Optional<YearMonth> monthInDescription(String description) {
        if (description == null || description.isBlank()) return Optional.empty();
        String upper = description.toUpperCase(Locale.ROOT);
        Matcher mRange = RANGE.matcher(upper);
        if (mRange.find() && MONTHS.containsKey(mRange.group(1))) {
            return Optional.of(YearMonth.of(Integer.parseInt(mRange.group(2)), MONTHS.get(mRange.group(1))));
        }
        Matcher mName = MONTH_NAME_YEAR.matcher(upper);
        if (mName.find() && MONTHS.containsKey(mName.group(1))) {
            return Optional.of(YearMonth.of(Integer.parseInt(mName.group(2)), MONTHS.get(mName.group(1))));
        }
        Matcher mIso = YEAR_MONTH_ISO.matcher(upper);
        if (mIso.find()) {
            return Optional.of(YearMonth.of(Integer.parseInt(mIso.group(1)), Integer.parseInt(mIso.group(2))));
        }
        return Optional.empty();
    }

    private BillingPeriod findOrCreatePeriod(YearMonth month) {
        return repositorioPeriodo.findByCode(month.toString()).orElseGet(() -> repositorioPeriodo.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), month.toString(), BillingCutType.ADVANCE, month.atDay(1), month.atEndOfMonth(), true)));
    }

    private void audit(Invoice invoice, String sourceName, Result result) {
        if (auditService == null) return;
        String status = switch (result.outcome()) {
            case APLICADO -> "IMPORTADO";
            case CONFLICTO, CON_PAGOS -> "RECHAZADO";
            default -> "OMITIDO";
        };
        // Clave propia (no el hash del archivo): no interfiere con la detección de PDF ya recibidos.
        String key = "pdf-detalle:" + invoice.getId();
        auditService.recordIfChanged(sourceName != null ? sourceName : invoice.getInvoiceNumber(), AUDIT_SOURCE, status, key,
                result.outcome() == Outcome.APLICADO ? 1 : 0, result.outcome() == Outcome.YA_CONSISTENTE ? 1 : 0,
                "Factura " + invoice.getInvoiceNumber() + ": " + result.detail());
    }

    private static BigDecimal sumBases(PdfExtractedMetadata meta) {
        return meta.items().stream().map(PdfMetadataExtractor.PdfLineItem::taxableBase).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String plain(Money money) {
        return money.amount().toPlainString();
    }
}
