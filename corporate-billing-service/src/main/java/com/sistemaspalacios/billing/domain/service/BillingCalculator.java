package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.ProrationResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure domain service for calculating billing items and assembling consolidated invoices (HU-10, HU-12, HU-13, HU-18).
 */
public class BillingCalculator {

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final String[] SPANISH_MONTHS = {
            "ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGT", "SEP", "OCT", "NOV", "DIC"
    };

    /**
     * Orden determinista de novedades dentro de un periodo: primero por fecha efectiva,
     * después por fecha de creación y finalmente por id. Sin este orden el resultado del
     * consolidado dependía del orden de llegada de la base de datos.
     */
    private static final Comparator<OrderUpdate> UPDATE_CHRONOLOGICAL_ORDER =
            Comparator.comparing(OrderUpdate::getEffectiveDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(OrderUpdate::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(u -> u.getId().toString());

    private final ProrationCalculator prorationCalculator;
    private final TaxCalculator taxCalculator;

    public BillingCalculator(ProrationCalculator prorationCalculator, TaxCalculator taxCalculator) {
        this.prorationCalculator = Objects.requireNonNull(prorationCalculator, "prorationCalculator cannot be null");
        this.taxCalculator = Objects.requireNonNull(taxCalculator, "taxCalculator cannot be null");
    }

    public BillingCalculator() {
        this(new ProrationCalculator(), new TaxCalculator());
    }

    /**
     * Resolves effective charge start date according to Rule 1 (HU-10):
     * Uses site.certificationDate, UNLESS an explicit commercial observation specifies another date.
     */
    public LocalDate resolveEffectiveBillingDate(SubscriptionSite site, OrderUpdate update, LocalDate periodStartDate) {
        if (update != null && update.getObservation() != null) {
            String obs = update.getObservation();
            Matcher matcher = DATE_PATTERN.matcher(obs);
            if (matcher.find()) {
                return LocalDate.parse(matcher.group(1));
            }
            if (update.getEffectiveDate() != null) {
                return update.getEffectiveDate();
            }
        }

        if (site.getCertificationDate() != null) {
            return site.getCertificationDate();
        }

        return periodStartDate;
    }

    /**
     * Generates a single consolidated invoice for a client, their sites, updates, and fees.
     */
    public Invoice calculateConsolidatedInvoice(
            CorporateClient client,
            BillingPeriod period,
            List<SubscriptionSite> sites,
            List<OrderUpdate> updates,
            List<InstallationFee> fees,
            List<TaxConceptValidity> conceptValidities
    ) {
        return calculateConsolidatedInvoice(client, period, sites, updates, fees, conceptValidities, Map.of());
    }

    /**
     * Variante que recibe la tarifa mensual que estaba vigente al INICIO del periodo para
     * cada sede (Sprint 10).
     * <p>
     * Es necesaria porque {@code sede_suscripcion.valor_mensual} ya refleja la tarifa
     * NUEVA cuando la sincronización de órdenes procesó un cambio de plan: sin este mapa,
     * el tramo anterior al cambio se cobraría a la tarifa nueva y el mes entero saldría
     * mal (de más al subir de plan, de menos al bajar).
     *
     * @param ratesAtPeriodStart tarifa por sede al inicio del periodo, normalmente tomada
     *                           de la versión anterior de la factura o de la factura del
     *                           periodo previo. Las sedes ausentes caen a
     *                           {@code sede.valor_mensual}.
     */
    public Invoice calculateConsolidatedInvoice(
            CorporateClient client,
            BillingPeriod period,
            List<SubscriptionSite> sites,
            List<OrderUpdate> updates,
            List<InstallationFee> fees,
            List<TaxConceptValidity> conceptValidities,
            Map<java.util.UUID, Money> ratesAtPeriodStart
    ) {
        Objects.requireNonNull(client, "client cannot be null");
        Objects.requireNonNull(period, "period cannot be null");
        Map<java.util.UUID, Money> openingRates = ratesAtPeriodStart != null ? ratesAtPeriodStart : Map.of();

        Invoice invoice = new Invoice(
                null,
                client.getId(),
                period.getId(),
                client.getBillingType(),
                client,
                LocalDate.now(),
                period.getEndDate().plusDays(15)
        );

        DateRange periodRange = period.toDateRange();
        List<InvoiceItem> calculatedItems = new ArrayList<>();

        // 1. Process recurring service charges for each billable site
        if (sites != null) {
            for (SubscriptionSite site : sites) {
                List<OrderUpdate> siteUpdates = (updates != null)
                        ? updates.stream()
                        .filter(u -> u.getSiteId().equals(site.getId()) && u.isIncludeInConsolidation())
                        .sorted(UPDATE_CHRONOLOGICAL_ORDER)
                        .toList()
                        : List.of();

                if (!isBillableInPeriod(site, siteUpdates, periodRange)) {
                    continue;
                }

                calculatedItems.addAll(calculateSiteItems(
                        client, period, periodRange, site, siteUpdates, invoice.getId(),
                        openingRates.get(site.getId())));
            }
        }

        // 2. Process installation fee installments (HU-12)
        if (fees != null) {
            for (InstallationFee fee : fees) {
                if (fee.hasPendingInstallments()) {
                    fee.incrementBilledInstallments();

                    String feeDescription = String.format(
                            "CUOTA INSTALACION (%d/%d) %s",
                            fee.getBilledInstallments(),
                            fee.getTotalInstallments(),
                            fee.getSiteId()
                    );

                    InvoiceItem feeItem = new InvoiceItem(
                            null,
                            invoice.getId(),
                            BillableItemType.CORPORATE_SERVICE,
                            fee.getSiteId(),
                            feeDescription,
                            BigDecimal.ONE,
                            fee.getInstallmentAmount(),
                            BigDecimal.ZERO,
                            true,
                            TaxCalculator.DEFAULT_IVA_RATE
                    );
                    calculatedItems.add(feeItem);
                }
            }
        }

        invoice.addItems(calculatedItems);

        // 4. Calculate taxes and official totals (HU-14)
        invoice.recalculateTotals();

        // Sprint 9 (HU-51): Las retenciones ya NO se calculan al emitir la factura.
        // Se calculan exclusivamente al momento del recaudo / pago.
        return invoice;
    }

    /**
     * Calcula los ítems de una sede aplicando la semántica económica real de cada
     * novedad del periodo (Sprint 10).
     * <p>
     * Hasta Sprint 9 el tipo de novedad se ignoraba: cualquier novedad solo desplazaba
     * la fecha de inicio del prorrateo, de modo que una BAJA terminaba cobrando como si
     * fuera un alta. Aquí se recorre la línea de tiempo del periodo y se emite un ítem
     * por cada tramo facturable:
     * <ul>
     *   <li><b>ALTA</b> / <b>REACTIVACION</b>: abre cobro desde la fecha efectiva.</li>
     *   <li><b>BAJA</b> / <b>SUSPENSION</b>: cierra el cobro el día anterior a la fecha
     *       efectiva, por lo que solo se cobra lo realmente consumido.</li>
     *   <li><b>CAMBIO_PLAN</b> / <b>CAMBIO_SERVICIO</b>: cierra el tramo a la tarifa
     *       anterior y abre uno nuevo a la tarifa vigente.</li>
     *   <li><b>TRASLADO_DIRECCION</b>: sin efecto económico, no parte el tramo.</li>
     * </ul>
     * <p>
     * <b>Convención de tarifas.</b> Se manejan dos tarifas distintas:
     * <ul>
     *   <li>La <b>tarifa de apertura</b> ({@code rateAtPeriodStart}) es la que estaba vigente
     *       el día 1 del periodo. Viene de la versión anterior de la factura o de la factura
     *       del periodo previo, porque {@code sede.valor_mensual} ya trae la tarifa NUEVA una
     *       vez que la sincronización de órdenes procesó el cambio de plan.</li>
     *   <li>La <b>tarifa entrante</b> es {@code novedad.valor} si viene informada, y si no
     *       {@code sede.valor_mensual}.</li>
     * </ul>
     * Cuando la tarifa de apertura no se conoce, ambos lados del cambio caen al mismo valor y
     * los tramos se vuelven a fusionar en una única línea de mes completo, que es el
     * comportamiento anterior a este cambio.
     */
    private List<InvoiceItem> calculateSiteItems(
            CorporateClient client,
            BillingPeriod period,
            DateRange periodRange,
            SubscriptionSite site,
            List<OrderUpdate> siteUpdates,
            java.util.UUID invoiceId,
            Money rateAtPeriodStart
    ) {
        String municipality = client.getMunicipality() != null ? client.getMunicipality() : "GENERAL";
        String serviceName = site.getSiteName() != null ? site.getSiteName() : "INTERNET DEDICADO";

        // Regla HU-10 preservada: una observación comercial que renuncia al prorrateo
        // cobra el mes completo sin importar las fechas de las novedades.
        Optional<OrderUpdate> waiver = siteUpdates.stream().filter(OrderUpdate::waivesProration).findFirst();
        if (waiver.isPresent()) {
            Money rate = resolveRate(waiver.get(), site);
            ProrationResult full = prorationCalculator.calculate(
                    rate, null, periodRange, period.getCutType(), true);
            // Es el cargo fijo del mes al plan indicado, no un concepto adicional: sin novedad de origen.
            return List.of(buildServiceItem(invoiceId, site, municipality, serviceName, periodRange, full, rate));
        }

        // Sin novedades: comportamiento base por fecha de certificación de la sede.
        if (siteUpdates.isEmpty()) {
            LocalDate effectiveDate = resolveEffectiveBillingDate(site, null, period.getStartDate());
            ProrationResult proration = prorationCalculator.calculate(
                    site.getMonthlyValue(), effectiveDate, periodRange, period.getCutType(), false);
            if (proration.effectiveDays() == 0) {
                return List.of(); // certificada el día 31 o después del cierre: nada que cobrar aún
            }
            InvoiceItem item = buildServiceItem(
                    invoiceId, site, municipality, serviceName, periodRange, proration, site.getMonthlyValue());
            if (effectiveDate != null && effectiveDate.isAfter(periodRange.startDate())) {
                item.setCoveredPeriod(effectiveDate, periodRange.endDate()); // certificada a mitad de mes
            }
            return List.of(item);
        }

        // Cargo fijo + incrementos (detalle de cobros por bloques). El servicio que regía el
        // día 1 se cobra como CARGO FIJO por los días que estuvo activo. Cada novedad que
        // cambia la tarifa agrega su INCREMENTO, (nuevo − anterior) × días desde su fecha
        // hasta que el servicio termina. La suma es la misma que cobrar cada tramo a su
        // tarifa, pero el cargo fijo no se altera y cada novedad queda en su propia línea.
        OrderUpdate first = siteUpdates.get(0);
        boolean opensPeriodClosed = isActivating(first.getUpdateType())
                && withinPeriod(effectiveDateOf(first, site, period), periodRange);
        boolean serviceActive = !opensPeriodClosed;

        // Tarifa vigente el día 1. Si se conoce la que se facturó antes (versión anterior de
        // la factura o factura del periodo previo) se usa esa, porque sede.valor_mensual ya
        // puede traer la tarifa NUEVA tras sincronizar un cambio de plan.
        Money currentRate = (rateAtPeriodStart != null && rateAtPeriodStart.isPositive())
                ? rateAtPeriodStart
                : site.getMonthlyValue();

        List<ChargeComponent> open = new ArrayList<>();
        List<ChargeComponent> closed = new ArrayList<>();

        if (serviceActive) {
            // La certificación a mitad de mes recorta el cargo fijo.
            LocalDate spanStart = periodRange.startDate();
            LocalDate certification = site.getCertificationDate();
            if (certification != null && certification.isAfter(spanStart) && !certification.isAfter(periodRange.endDate())) {
                spanStart = certification;
            }
            open.add(new ChargeComponent(spanStart, currentRate, currentRate, null));
        }

        for (OrderUpdate update : siteUpdates) {
            LocalDate effective = effectiveDateOf(update, site, period);
            OrderUpdateType type = update.getUpdateType();

            if (type == OrderUpdateType.TRASLADO_DIRECCION) {
                continue; // sin efecto económico
            }
            if (effective == null || effective.isAfter(periodRange.endDate())) {
                continue; // fuera del periodo: no altera este consolidado
            }
            LocalDate boundary = effective.isBefore(periodRange.startDate()) ? periodRange.startDate() : effective;

            switch (type) {
                case ALTA, REACTIVACION -> {
                    Money rate = resolveRate(update, site);
                    if (serviceActive) {
                        addIncrement(open, boundary, rate.minus(currentRate), rate, update.getId());
                    } else {
                        open.add(new ChargeComponent(boundary, rate, rate, update.getId()));
                        serviceActive = true;
                    }
                    currentRate = rate;
                }
                case CAMBIO_PLAN, CAMBIO_SERVICIO -> {
                    Money rate = resolveRate(update, site);
                    if (serviceActive) {
                        addIncrement(open, boundary, rate.minus(currentRate), rate, update.getId());
                    }
                    currentRate = rate; // si está suspendido, rige al reactivar
                }
                case BAJA, SUSPENSION -> {
                    if (serviceActive) {
                        for (ChargeComponent c : open) {
                            c.end = boundary.minusDays(1);
                            closed.add(c);
                        }
                        open.clear();
                    }
                    serviceActive = false;
                }
                case TRASLADO_DIRECCION -> { /* ya filtrado arriba */ }
            }
        }
        for (ChargeComponent c : open) {
            c.end = periodRange.endDate();
            closed.add(c);
        }

        // Materializa: los componentes positivos son conceptos; los negativos (bajada de
        // plan) no pueden ser una línea negativa en la factura electrónica y se aplican
        // como descuento, primero sobre el cargo fijo y luego sobre las demás líneas.
        List<InvoiceItem> items = new ArrayList<>();
        Money pendingDiscount = Money.ZERO;
        InvoiceItem fixedItem = null;
        for (ChargeComponent c : closed) {
            if (c.end.isBefore(c.start) || c.delta.isZero()) {
                continue;
            }
            boolean negative = c.delta.isNegative();
            Money magnitude = negative ? Money.ZERO.minus(c.delta) : c.delta;
            ProrationResult proration = prorationCalculator.calculateSegment(magnitude, c.start, c.end, periodRange);
            if (proration.effectiveDays() == 0) {
                continue;
            }
            if (negative) {
                pendingDiscount = pendingDiscount.plus(proration.proratedAmount());
                continue;
            }
            boolean isIncrement = c.sourceUpdateId != null && !c.delta.equals(c.rate);
            String detail = isIncrement ? serviceName + " " + INCREMENT_LABEL : serviceName;
            InvoiceItem item = buildServiceItem(invoiceId, site, municipality, detail,
                    new DateRange(c.start, c.end), proration, c.rate);
            item.setSourceUpdateId(c.sourceUpdateId);
            items.add(item);
            if (c.sourceUpdateId == null) {
                fixedItem = item;
            }
        }
        applyDowngradeDiscount(items, fixedItem, pendingDiscount);
        return items;
    }

    /** Sufijo de la descripción Siigo de una línea de incremento por novedad. */
    static final String INCREMENT_LABEL = "INCREMENTO POR NOVEDAD";

    /**
     * Agrega el incremento de una novedad. Si otro componente arranca ese mismo día (por
     * ejemplo, un cambio de plan efectivo el día 1, o retroactivo), aún no se cobró ningún
     * día a la tarifa anterior: se ajusta ese componente en lugar de abrir otra línea.
     */
    private static void addIncrement(List<ChargeComponent> open, LocalDate start, Money delta,
                                     Money newRate, java.util.UUID sourceUpdateId) {
        if (delta.isZero()) {
            return;
        }
        for (int i = open.size() - 1; i >= 0; i--) {
            ChargeComponent c = open.get(i);
            if (c.start.equals(start)) {
                c.delta = c.delta.plus(delta);
                c.rate = newRate;
                if (c.delta.isZero()) {
                    open.remove(i);
                }
                return;
            }
        }
        open.add(new ChargeComponent(start, delta, newRate, sourceUpdateId));
    }

    /**
     * Reparte el descuento por bajadas de plan: primero el cargo fijo y, si lo supera, las
     * demás líneas de la sede empezando por la más reciente. La suma de la sede queda igual
     * a cobrar cada tramo a su tarifa.
     */
    private static void applyDowngradeDiscount(List<InvoiceItem> items, InvoiceItem fixedItem, Money discount) {
        Money remaining = discount;
        List<InvoiceItem> order = new ArrayList<>();
        if (fixedItem != null) order.add(fixedItem);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) != fixedItem) order.add(items.get(i));
        }
        for (InvoiceItem item : order) {
            if (!remaining.isPositive()) break;
            Money room = item.getTaxableBase();
            Money applied = remaining.isGreaterThan(room) ? room : remaining;
            if (applied.isPositive()) {
                item.addFixedDiscount(applied);
                remaining = remaining.minus(applied);
            }
        }
    }

    /**
     * Parte del cobro de una sede: el cargo fijo (sin novedad) o el incremento que aporta
     * una novedad desde {@code start}. {@code delta} es el aporte mensual (negativo en una
     * bajada de plan) y {@code rate} la tarifa mensual total que queda vigente.
     */
    private static final class ChargeComponent {
        final LocalDate start;
        LocalDate end;
        Money delta;
        Money rate;
        final java.util.UUID sourceUpdateId;

        ChargeComponent(LocalDate start, Money delta, Money rate, java.util.UUID sourceUpdateId) {
            this.start = start;
            this.delta = delta;
            this.rate = rate;
            this.sourceUpdateId = sourceUpdateId;
        }
    }

    private InvoiceItem buildServiceItem(
            java.util.UUID invoiceId,
            SubscriptionSite site,
            String municipality,
            String serviceName,
            DateRange descriptionRange,
            ProrationResult proration,
            Money baseMonthlyRate
    ) {
        String description = formatItemDescription(municipality, "CORP", serviceName, descriptionRange);
        InvoiceItem item = new InvoiceItem(
                null,
                invoiceId,
                BillableItemType.CORPORATE_SERVICE,
                site.getId(),
                description,
                BigDecimal.ONE,
                proration.proratedAmount(),
                BigDecimal.ZERO,
                true,
                TaxCalculator.DEFAULT_IVA_RATE
        );
        item.setBaseMonthlyRate(baseMonthlyRate);
        item.setCoveredPeriod(descriptionRange.startDate(), descriptionRange.endDate());
        return item;
    }

    /**
     * Decide si una sede debe entrar en el consolidado del periodo (Sprint 10).
     * <p>
     * Antes bastaba con {@code site.isActive()}, y eso abría un agujero: cuando el sistema
     * de órdenes procesaba una BAJA, desactivaba la sede, y al facturar o regenerar el mes
     * la sede desaparecía por completo de la factura. Los días que el cliente sí consumió
     * antes de la baja no se cobraban nunca.
     * <p>
     * Ahora una sede inactiva sigue siendo facturable si en este periodo tiene una novedad
     * de baja o suspensión con fecha efectiva dentro del rango: esa fecha es la que define
     * hasta cuándo cobrar. Una sede inactiva sin novedades en el periodo (dada de baja hace
     * meses) se sigue omitiendo.
     */
    private boolean isBillableInPeriod(SubscriptionSite site, List<OrderUpdate> siteUpdates, DateRange periodRange) {
        if (site.isActive()) {
            return true;
        }
        return siteUpdates.stream()
                .filter(u -> isDeactivating(u.getUpdateType()))
                .map(OrderUpdate::getEffectiveDate)
                .anyMatch(date -> date != null && periodRange.contains(date));
    }

    /** Tarifa que entra en vigor con la novedad; cae a la de la sede si no viene informada. */
    private Money resolveRate(OrderUpdate update, SubscriptionSite site) {
        if (update != null && update.getValue() != null && update.getValue().isPositive()) {
            return update.getValue();
        }
        return site.getMonthlyValue();
    }

    private LocalDate effectiveDateOf(OrderUpdate update, SubscriptionSite site, BillingPeriod period) {
        if (update.getObservation() != null) {
            Matcher matcher = DATE_PATTERN.matcher(update.getObservation());
            if (matcher.find()) {
                return LocalDate.parse(matcher.group(1));
            }
        }
        if (update.getEffectiveDate() != null) {
            return update.getEffectiveDate();
        }
        // Sin fecha propia, una novedad de activación hereda la certificación de la sede.
        if (isActivating(update.getUpdateType()) && site.getCertificationDate() != null) {
            return site.getCertificationDate();
        }
        return period.getStartDate();
    }

    private static boolean isActivating(OrderUpdateType type) {
        return type == OrderUpdateType.ALTA || type == OrderUpdateType.REACTIVACION;
    }

    private static boolean isDeactivating(OrderUpdateType type) {
        return type == OrderUpdateType.BAJA || type == OrderUpdateType.SUSPENSION;
    }

    private static boolean withinPeriod(LocalDate date, DateRange range) {
        return date != null && range.contains(date);
    }

    /**
     * Formats line item description per real Siigo pattern (HU-18, Sprint 9):
     * e.g. "LA CRUZ.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026", "PLAZUELAS INT.FIB.DEDI 200MB GR 12-31 AGT 2026",
     * "NT.FIB.CORP.200MB GR 01-31 AGT 2026"
     */
    public String formatItemDescription(String prefix, String detail, DateRange range) {
        String base;
        String rawDetail = detail != null ? detail.toUpperCase() : "SERVICIO";
        if (prefix != null && !prefix.isBlank()) {
            String trimmedPrefix = prefix.trim().toUpperCase();
            if (rawDetail.startsWith(" ") || rawDetail.startsWith(".")) {
                base = trimmedPrefix + rawDetail.stripTrailing();
            } else {
                base = trimmedPrefix + "." + rawDetail.trim();
            }
        } else {
            base = rawDetail.trim();
        }

        String startDay = String.format("%02d", range.startDate().getDayOfMonth());
        String endDay = String.format("%02d", range.endDate().getDayOfMonth());
        String monthYear = SPANISH_MONTHS[range.endDate().getMonthValue() - 1] + " " + range.endDate().getYear();

        return String.format("%s GR %s-%s %s", base, startDay, endDay, monthYear);
    }

    public String formatItemDescription(String municipality, String serviceType, String detail, DateRange range) {
        String prefix = municipality != null ? municipality.trim().toUpperCase() : "";
        if (serviceType != null && !serviceType.isBlank() && !"CORP".equalsIgnoreCase(serviceType)) {
            prefix = prefix.isEmpty() ? serviceType.trim().toUpperCase() : prefix + "." + serviceType.trim().toUpperCase();
        }
        return formatItemDescription(prefix, detail, range);
    }
}
