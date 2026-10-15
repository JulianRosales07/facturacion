package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentInvoice;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioDocumentoAjuste;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto;
import com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto.ChargeBlock;
import com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto.ChargeLine;
import com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto.PreviousDebt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agrupa los conceptos de una factura en los bloques que ve el cliente:
 * <ul>
 *   <li><b>Cargos fijos</b>: el servicio de cada sede al plan vigente al inicio del mes
 *       (conceptos sin novedad de origen).</li>
 *   <li><b>Otros conceptos</b>: lo que agregan las novedades (altas, incrementos por cambio
 *       de plan), las cuotas de instalación y los descuentos por bajada de plan.</li>
 *   <li><b>Deuda anterior</b>: saldo pendiente de las facturas oficiales anteriores.</li>
 * </ul>
 * Solo agrupa: los valores salen de los conceptos persistidos y los bloques suman
 * exactamente el total de la factura.
 */
@Service
@Transactional(readOnly = true)
public class InvoiceChargeBreakdownService {

    private final RepositorioFactura repositorioFactura;
    private final RepositorioPeriodo repositorioPeriodo;
    private final RepositorioNovedad repositorioNovedad;
    private final RepositorioSede repositorioSede;
    private final RepositorioPago repositorioPago;
    private final RepositorioDocumentoAjuste repositorioDocumentoAjuste;

    public InvoiceChargeBreakdownService(RepositorioFactura repositorioFactura, RepositorioPeriodo repositorioPeriodo,
                                         RepositorioNovedad repositorioNovedad, RepositorioSede repositorioSede,
                                         RepositorioPago repositorioPago, RepositorioDocumentoAjuste repositorioDocumentoAjuste) {
        this.repositorioFactura = repositorioFactura;
        this.repositorioPeriodo = repositorioPeriodo;
        this.repositorioNovedad = repositorioNovedad;
        this.repositorioSede = repositorioSede;
        this.repositorioPago = repositorioPago;
        this.repositorioDocumentoAjuste = repositorioDocumentoAjuste;
    }

    public ChargeBreakdownDto breakdown(Invoice invoice) {
        Set<UUID> updateIds = invoice.getItems().stream().map(InvoiceItem::getSourceUpdateId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, OrderUpdate> updates = repositorioNovedad.findAllById(updateIds).stream()
                .collect(Collectors.toMap(OrderUpdate::getId, Function.identity()));
        Set<UUID> siteIds = invoice.getItems().stream().map(InvoiceItem::getSiteId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        // "SEDE CENTRO (orden PRB-OCT-A)": como en la referencia, cada línea dice de dónde viene.
        Map<UUID, String> siteNames = repositorioSede.findAllById(siteIds).stream()
                .collect(Collectors.toMap(SubscriptionSite::getId,
                        s -> (s.getSiteName() != null ? s.getSiteName() : "Sede")
                                + (s.getExternalOrderCode() != null ? " (orden " + s.getExternalOrderCode() + ")" : "")));

        BlockBuilder fixed = new BlockBuilder();
        BlockBuilder other = new BlockBuilder();

        for (InvoiceItem item : invoice.getItems()) {
            Money gross = item.getUnitPrice().multiply(item.getQuantity());
            String site = item.getSiteId() != null ? siteNames.getOrDefault(item.getSiteId(), "") : "";
            LocalDate[] range = coveredRange(item);
            boolean isFixedCharge = invoice.isExternalDocument()
                    ? isOfficialFixedCharge(item, range)
                    : item.getSourceUpdateId() == null && !item.isInstallationFeeLine() && item.getSiteId() != null;

            if (!isFixedCharge) {
                String label = item.isInstallationFeeLine() || invoice.isExternalDocument() ? item.getDescription()
                        : noveltyLabel(updates.get(item.getSourceUpdateId()), site, item);
                other.add(new ChargeLine(label, range[0], range[1], item.getTaxableBase().amount(),
                        item.getSourceUpdateId(), categoryOf(item).title()), item.getTaxableBase(), item.getIvaValue());
                continue;
            }

            String fixedLabel = invoice.isExternalDocument() ? item.getDescription() : fixedLabel(site, item);
            if (!item.getDiscountValue().isPositive()) {
                fixed.add(new ChargeLine(fixedLabel, range[0], range[1], gross.amount(), null), gross, item.getIvaValue());
                continue;
            }
            // Cargo fijo con descuento por bajada de plan: el cargo fijo se muestra completo y
            // el descuento va a Otros conceptos. El IVA se reparte para que cada bloque cuadre.
            Money fixedIva = gross.percentage(item.getIvaPercentage());
            Money discountIva = item.getIvaValue().minus(fixedIva);
            fixed.add(new ChargeLine(fixedLabel, range[0], range[1], gross.amount(), null), gross, fixedIva);
            Money discount = Money.ZERO.minus(item.getDiscountValue());
            String discountLabel = invoice.isExternalDocument() ? "Descuento · " + item.getDescription()
                    : "Descuento por bajada de plan o compensación · " + site;
            other.add(new ChargeLine(discountLabel, range[0], range[1], discount.amount(), null,
                    ChargeBreakdownDto.Category.AJUSTES.title()), discount, discountIva);
        }

        PreviousDebt debt = previousDebt(invoice);
        Money total = invoice.getTotalAmount();
        return new ChargeBreakdownDto(debt, fixed.build(), other.build(), total.amount(),
                total.amount().add(debt.saldo()));
    }

    /**
     * Palabras con que Siigo describe lo que no es el cargo mensual del servicio. "NOV" sola
     * no sirve: es también la abreviatura de noviembre en "GR 01-30 NOV 2026".
     */
    private static final java.util.regex.Pattern OTHER_CONCEPT_WORDS = java.util.regex.Pattern.compile(
            "NOVEDAD|^NOV-|DIFERENCIAL|INCREMENTO|AJUSTE|CUOTA|INSTALACI[OÓ]N|INTER[EÉ]S|MORA|RECONEXI[OÓ]N|ADICIONAL|PRORRATEO"
                    + "|GESTI[OÓ]N (DE )?COBRO|DEVOLUCI[OÓ]N|REVERSI[OÓ]N|DESCUENTO");

    private static final java.util.regex.Pattern COLLECTION_WORDS =
            java.util.regex.Pattern.compile("INTER[EÉ]S|MORA|GESTI[OÓ]N (DE )?COBRO");
    private static final java.util.regex.Pattern ADJUSTMENT_WORDS =
            java.util.regex.Pattern.compile("AJUSTE|DEVOLUCI[OÓ]N|REVERSI[OÓ]N|DESCUENTO");

    /** Subtítulo de una línea de Otros conceptos según su código y descripción. */
    private static ChargeBreakdownDto.Category categoryOf(InvoiceItem item) {
        String text = ((item.getProductCode() != null ? item.getProductCode() + " " : "")
                + (item.getDescription() != null ? item.getDescription() : "")).toUpperCase(Locale.ROOT);
        if (item.isInstallationFeeLine() || text.contains("CUOTA") || text.matches(".*INSTALACI[OÓ]N.*")) {
            return ChargeBreakdownDto.Category.CUOTAS;
        }
        if (COLLECTION_WORDS.matcher(text).find()) return ChargeBreakdownDto.Category.COBRANZA;
        if (ADJUSTMENT_WORDS.matcher(text).find() || item.getTaxableBase().isNegative()) {
            return ChargeBreakdownDto.Category.AJUSTES;
        }
        return ChargeBreakdownDto.Category.NOVEDADES;
    }

    /** Rango "GR dd-dd MMM aaaa" de las descripciones de Siigo y del calculador. */
    private static final java.util.regex.Pattern RANGE_IN_DESCRIPTION = java.util.regex.Pattern.compile(
            "GR\\s+(\\d{1,2})-(\\d{1,2})\\s+([A-Z]{3})\\s+(\\d{4})");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("ENE", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("ABR", 4), Map.entry("MAY", 5),
            Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AGT", 8), Map.entry("AGO", 8), Map.entry("SEP", 9),
            Map.entry("SET", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DIC", 12));

    /**
     * Documento de Siigo: es cargo fijo salvo que su código o descripción lo identifique como
     * novedad, ajuste, cuota o interés, o que cubra un rango que empieza después del día 1
     * (un alta prorrateada). Un rango que empieza el día 1 y termina antes (una baja) sigue
     * siendo el cargo fijo del mes.
     */
    private static boolean isOfficialFixedCharge(InvoiceItem item, LocalDate[] range) {
        String text = ((item.getProductCode() != null ? item.getProductCode() + " " : "")
                + (item.getDescription() != null ? item.getDescription() : "")).toUpperCase(Locale.ROOT);
        String code = item.getProductCode() != null ? item.getProductCode().toUpperCase(Locale.ROOT) : "";
        if (OTHER_CONCEPT_WORDS.matcher(text).find() || OTHER_CONCEPT_WORDS.matcher(code).find()) {
            return false;
        }
        return range[0] == null || range[0].getDayOfMonth() == 1;
    }

    /** Fechas guardadas en el concepto; si no las tiene (Siigo, líneas anteriores a V25), las de su descripción. */
    private static LocalDate[] coveredRange(InvoiceItem item) {
        if (item.getCoveredFrom() != null && item.getCoveredTo() != null) {
            return new LocalDate[] {item.getCoveredFrom(), item.getCoveredTo()};
        }
        if (item.getDescription() != null) {
            var m = RANGE_IN_DESCRIPTION.matcher(item.getDescription().toUpperCase(Locale.ROOT));
            Integer month = m.find() ? MONTHS.get(m.group(3)) : null;
            if (month != null) {
                try {
                    var ym = java.time.YearMonth.of(Integer.parseInt(m.group(4)), month);
                    int to = Math.min(Integer.parseInt(m.group(2)), ym.lengthOfMonth());
                    return new LocalDate[] {ym.atDay(Integer.parseInt(m.group(1))), ym.atDay(to)};
                } catch (RuntimeException ignored) {
                    // descripción con un rango inválido: se muestra sin fechas
                }
            }
        }
        return new LocalDate[] {null, null};
    }

    private static String fixedLabel(String site, InvoiceItem item) {
        String rate = item.getBaseMonthlyRate() != null ? " · plan " + pesos(item.getBaseMonthlyRate()) : "";
        return "Servicio " + site + rate;
    }

    private static String noveltyLabel(OrderUpdate update, String site, InvoiceItem item) {
        if (update == null) {
            return item.getDescription();
        }
        String newRate = update.getValue() != null && update.getValue().isPositive() ? " a " + pesos(update.getValue()) : "";
        String when = update.getEffectiveDate() != null ? " · desde " + shortDate(update.getEffectiveDate()) : "";
        String prefix = update.isAdjustment() ? "Ajuste de periodo anterior · " : "";
        return prefix + switch (update.getUpdateType()) {
            case ALTA -> "Alta de sede · " + site + when;
            case REACTIVACION -> "Reactivación · " + site + when;
            case CAMBIO_PLAN -> "Cambio de plan" + newRate + " · " + site + when;
            case CAMBIO_SERVICIO -> "Cambio de servicio" + newRate + " · " + site + when;
            case BAJA -> "Baja · " + site + when;
            case SUSPENSION -> "Suspensión · " + site + when;
            default -> item.getDescription();
        };
    }

    private static final String[] SHORT_MONTHS = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};

    /** 16-oct-2026 */
    private static String shortDate(LocalDate date) {
        return String.format("%02d-%s-%d", date.getDayOfMonth(), SHORT_MONTHS[date.getMonthValue() - 1], date.getYear());
    }

    private static String pesos(Money value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("es-CO"));
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("$ #,##0", symbols).format(value.amount());
    }

    /**
     * Deuda anterior: facturas oficiales de Siigo del mismo cliente emitidas antes del
     * periodo de esta factura, vigentes. Se usan solo las de Siigo porque son las que
     * cartera reconoce; las internas, al emitirse, vuelven como documento de Siigo.
     */
    private PreviousDebt previousDebt(Invoice invoice) {
        LocalDate periodStart = repositorioPeriodo.findById(invoice.getPeriodId())
                .map(BillingPeriod::getStartDate).orElse(invoice.getIssueDate());
        Money charges = Money.ZERO;
        Money pending = Money.ZERO;
        int count = 0;
        for (Invoice previous : repositorioFactura.findByClientId(invoice.getClientId())) {
            if (previous.getId().equals(invoice.getId()) || !previous.isExternalDocument()
                    || !previous.getStatus().isCurrent() || previous.getIssueDate() == null
                    || !previous.getIssueDate().isBefore(periodStart)) {
                continue;
            }
            enrichBalances(previous);
            charges = charges.plus(previous.getTotalAmount().minus(previous.getCreditNoteAmount()));
            pending = pending.plus(previous.getPendingBalance());
            count++;
        }
        Money paid = charges.minus(pending);
        return new PreviousDebt(charges.amount(), paid.amount(), pending.amount(), count);
    }

    /** Mismo criterio que cartera: notas crédito aplicadas y pagos APLICADO cruzados a la factura. */
    private void enrichBalances(Invoice invoice) {
        Money creditNotes = repositorioDocumentoAjuste
                .findByInvoiceIdAndStatus(invoice.getId(), AdjustmentDocumentStatus.APPLIED).stream()
                .map(AdjustmentDocument::getNetAmount).reduce(Money.ZERO, Money::plus);
        invoice.setCreditNoteAmount(creditNotes);
        Money paid = Money.ZERO;
        for (Payment payment : repositorioPago.findAppliedPaymentsByInvoiceId(invoice.getId())) {
            for (PaymentInvoice crossing : payment.getInvoiceCrossings()) {
                if (crossing.getInvoiceId().equals(invoice.getId())) {
                    paid = paid.plus(crossing.getAppliedAmount());
                }
            }
        }
        invoice.setPaidAmount(paid);
    }

    private static int categoryOrder(String title) {
        for (ChargeBreakdownDto.Category c : ChargeBreakdownDto.Category.values()) {
            if (c.title().equals(title)) return c.ordinal();
        }
        return -1; // cargos fijos: sin subtítulo
    }

    private static final class BlockBuilder {
        private final List<ChargeLine> lines = new ArrayList<>();
        private Money subtotal = Money.ZERO;
        private Money taxes = Money.ZERO;

        void add(ChargeLine line, Money base, Money iva) {
            lines.add(line);
            subtotal = subtotal.plus(base);
            taxes = taxes.plus(iva);
        }

        ChargeBlock build() {
            // Por subtítulo (en el orden de Category), luego por fecha y descripción.
            lines.sort(java.util.Comparator.comparingInt((ChargeLine l) -> categoryOrder(l.categoria()))
                    .thenComparing(l -> l.fechaInicial() == null ? LocalDate.MIN : l.fechaInicial())
                    .thenComparing(ChargeLine::descripcion));
            return new ChargeBlock(List.copyOf(lines), subtotal.amount(), taxes.amount(), subtotal.plus(taxes).amount());
        }
    }
}
