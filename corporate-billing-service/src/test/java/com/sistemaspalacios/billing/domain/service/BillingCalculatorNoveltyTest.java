package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 10: efecto económico de cada tipo de novedad a mitad de periodo.
 * <p>
 * Escenario base: sede de $500.000/mes, periodo 2026-09 (30 días), novedad efectiva el 16.
 * Mes comercial de 30 días, así que del 1 al 15 son 15 días = factor 0,5 y del 16 al 30
 * también son 15 días = factor 0,5.
 */
class BillingCalculatorNoveltyTest {

    private static final Money RATE_500K = Money.of(500_000);

    private BillingCalculator calculator;
    private BillingPeriod september;
    private CorporateClient client;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        calculator = new BillingCalculator();
        september = new BillingPeriod(
                UUID.randomUUID(), "2026-09", BillingCutType.ADVANCE,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), true);
        client = new CorporateClient(
                UUID.randomUUID(), "900245045", "CLIENTE CORP SAS", "Calle 100",
                "factura@corp.com", "3101234567", (short) 1, (short) 1,
                BillingType.F1, false, "Pasto", TaxProfile.withoutWithholding("Pasto"));
        siteId = UUID.randomUUID();
    }

    private SubscriptionSite site(Money monthlyValue, boolean active) {
        return new SubscriptionSite(
                siteId, client.getId(), "ORD-001", OrderOrigin.SPE, "NIC-01",
                "SEDE CENTRO", "Dir 1", monthlyValue, LocalDate.of(2026, 1, 1), active);
    }

    private OrderUpdate update(OrderUpdateType type, LocalDate effective, Money value) {
        return new OrderUpdate(
                UUID.randomUUID(), siteId, type, september.getId(), value, effective, true, null);
    }

    private Invoice calculate(SubscriptionSite site, List<OrderUpdate> updates, Map<UUID, Money> openingRates) {
        return calculator.calculateConsolidatedInvoice(
                client, september, List.of(site), updates, List.of(), List.of(), openingRates);
    }

    @Test
    @DisplayName("Referencia: sin novedades se cobra el mes completo")
    void testFullMonthBaseline() {
        Invoice invoice = calculate(site(RATE_500K, true), List.of(), Map.of());

        assertEquals(1, invoice.getItems().size());
        assertEquals(Money.of(500_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(595_000), invoice.getTotalAmount());
        assertEquals(RATE_500K, invoice.getItems().get(0).getBaseMonthlyRate());
    }

    @Test
    @DisplayName("BAJA el día 16: cobra solo del 1 al 15, no del 16 al 30")
    void testBajaChargesOnlyConsumedDays() {
        Invoice invoice = calculate(
                site(RATE_500K, true),
                List.of(update(OrderUpdateType.BAJA, LocalDate.of(2026, 9, 16), Money.ZERO)),
                Map.of());

        assertEquals(1, invoice.getItems().size());
        InvoiceItem item = invoice.getItems().get(0);
        assertTrue(item.getDescription().contains("GR 01-15 SEP 2026"),
                "El rango cobrado debe ser 01-15, no 16-30. Fue: " + item.getDescription());
        assertEquals(Money.of(250_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(297_500), invoice.getTotalAmount());
    }

    @Test
    @DisplayName("Hueco corregido: una sede ya desactivada por la BAJA sigue cobrando el tramo consumido")
    void testDeactivatedSiteStillBillsConsumedSegment() {
        // El sistema de órdenes ya puso la sede en INACTIVO al procesar la baja.
        SubscriptionSite deactivated = site(RATE_500K, false);

        Invoice invoice = calculate(
                deactivated,
                List.of(update(OrderUpdateType.BAJA, LocalDate.of(2026, 9, 16), Money.ZERO)),
                Map.of());

        assertEquals(1, invoice.getItems().size(),
                "La sede inactiva dada de baja dentro del periodo no debe desaparecer de la factura");
        assertEquals(Money.of(250_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("Una sede inactiva SIN novedades en el periodo se sigue omitiendo")
    void testLongInactiveSiteIsSkipped() {
        Invoice invoice = calculate(site(RATE_500K, false), List.of(), Map.of());
        assertEquals(0, invoice.getItems().size());
    }

    @Test
    @DisplayName("SUSPENSION el día 16 se comporta como la baja")
    void testSuspensionChargesOnlyConsumedDays() {
        Invoice invoice = calculate(
                site(RATE_500K, true),
                List.of(update(OrderUpdateType.SUSPENSION, LocalDate.of(2026, 9, 16), Money.ZERO)),
                Map.of());

        assertEquals(Money.of(250_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("ALTA el día 16: cobra del 16 al 30")
    void testAltaProratesForward() {
        Invoice invoice = calculate(
                site(RATE_500K, true),
                List.of(update(OrderUpdateType.ALTA, LocalDate.of(2026, 9, 16), RATE_500K)),
                Map.of());

        assertEquals(1, invoice.getItems().size());
        assertTrue(invoice.getItems().get(0).getDescription().contains("GR 16-30 SEP 2026"));
        assertEquals(Money.of(250_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("TRASLADO_DIRECCION no tiene efecto económico: mes completo")
    void testAddressTransferHasNoEconomicEffect() {
        Invoice invoice = calculate(
                site(RATE_500K, true),
                List.of(update(OrderUpdateType.TRASLADO_DIRECCION, LocalDate.of(2026, 9, 16), Money.ZERO)),
                Map.of());

        assertEquals(Money.of(500_000), invoice.getSubtotalAmount());
    }

    // ---------------------------------------------------------------------------
    // Cambio de plan: el hueco de la tarifa anterior
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("SUBE de plan 500k -> 800k el día 16: cargo fijo 500.000 + incremento 150.000 = 650.000, no 800.000")
    void testPlanUpgradeSplitsMonthWithBothRates() {
        // La sincronización de órdenes ya dejó valor_mensual en la tarifa NUEVA.
        SubscriptionSite siteAfterSync = site(Money.of(800_000), true);
        OrderUpdate change = update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 9, 16), Money.of(800_000));

        Invoice invoice = calculate(siteAfterSync, List.of(change),
                Map.of(siteId, RATE_500K)); // tarifa de apertura desde la versión anterior

        assertEquals(2, invoice.getItems().size());

        InvoiceItem fixed = invoice.getItems().get(0);
        InvoiceItem increment = invoice.getItems().get(1);

        assertTrue(fixed.getDescription().contains("GR 01-30 SEP 2026"), fixed.getDescription());
        assertEquals(RATE_500K, fixed.getBaseMonthlyRate());
        assertEquals(Money.of(500_000), fixed.getTaxableBase());
        assertNull(fixed.getSourceUpdateId(), "El cargo fijo es el plan del día 1, no una novedad");

        assertTrue(increment.getDescription().contains(BillingCalculator.INCREMENT_LABEL + " GR 16-30 SEP 2026"),
                increment.getDescription());
        assertEquals(Money.of(800_000), increment.getBaseMonthlyRate());
        assertEquals(Money.of(150_000), increment.getTaxableBase()); // (800.000 − 500.000) × 15/30
        assertEquals(change.getId(), increment.getSourceUpdateId());
        assertEquals(LocalDate.of(2026, 9, 16), increment.getCoveredFrom());

        assertEquals(Money.of(650_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(773_500), invoice.getTotalAmount());
    }

    @Test
    @DisplayName("BAJA de plan 500k -> 300k el día 16: cargo fijo 500.000 con descuento de 100.000, sin líneas negativas")
    void testPlanDowngradeSplitsMonthWithBothRates() {
        SubscriptionSite siteAfterSync = site(Money.of(300_000), true);

        Invoice invoice = calculate(
                siteAfterSync,
                List.of(update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 9, 16), Money.of(300_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(1, invoice.getItems().size(), "La bajada no genera una línea negativa");
        InvoiceItem fixed = invoice.getItems().get(0);
        assertEquals(Money.of(500_000), fixed.getUnitPrice());
        assertEquals(Money.of(100_000), fixed.getDiscountValue()); // (500.000 − 300.000) × 15/30
        assertEquals(Money.of(400_000), fixed.getTaxableBase());

        // Sin la tarifa de apertura esto daba 300.000: fuga de ingresos de 100.000
        assertEquals(Money.of(400_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(476_000), invoice.getTotalAmount());
    }

    @Test
    @DisplayName("Sin tarifa de apertura conocida, el cambio de plan degrada a un ÚNICO ítem de mes completo")
    void testPlanChangeWithoutOpeningRateMergesIntoSingleLine() {
        SubscriptionSite siteAfterSync = site(Money.of(800_000), true);

        Invoice invoice = calculate(
                siteAfterSync,
                List.of(update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 9, 16), Money.of(800_000))),
                Map.of()); // no hay versión anterior ni factura del mes previo

        // Ambos tramos quedan a la misma tarifa: se fusionan en una sola línea en lugar de
        // mostrar dos renglones de 400.000 que confunden en la representación gráfica.
        assertEquals(1, invoice.getItems().size());
        assertEquals(Money.of(800_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("CAMBIO_SERVICIO parte el mes igual que el cambio de plan")
    void testServiceChangeSplitsMonth() {
        Invoice invoice = calculate(
                site(Money.of(600_000), true),
                List.of(update(OrderUpdateType.CAMBIO_SERVICIO, LocalDate.of(2026, 9, 16), Money.of(600_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(Money.of(550_000), invoice.getSubtotalAmount()); // 250.000 + 300.000
    }

    @Test
    @DisplayName("Alta y baja en el mismo mes: solo se cobra el tramo intermedio")
    void testAltaAndBajaInSameMonth() {
        Invoice invoice = calculate(
                site(RATE_500K, false),
                List.of(
                        update(OrderUpdateType.ALTA, LocalDate.of(2026, 9, 6), RATE_500K),
                        update(OrderUpdateType.BAJA, LocalDate.of(2026, 9, 21), Money.ZERO)),
                Map.of());

        assertEquals(1, invoice.getItems().size());
        InvoiceItem item = invoice.getItems().get(0);
        assertTrue(item.getDescription().contains("GR 06-20 SEP 2026"),
                "Debe cobrar del 6 al 20. Fue: " + item.getDescription());
        assertEquals(Money.of(250_000), invoice.getSubtotalAmount()); // 15 días
    }

    @Test
    @DisplayName("La renuncia al prorrateo (HU-10) sigue cobrando el mes completo pese a la novedad")
    void testProrationWaiverStillWins() {
        OrderUpdate waiver = new OrderUpdate(
                UUID.randomUUID(), siteId, OrderUpdateType.ALTA, september.getId(),
                RATE_500K, LocalDate.of(2026, 9, 16), true, "Acuerdo comercial: no cobrar prorrateo");

        Invoice invoice = calculate(site(RATE_500K, true), List.of(waiver), Map.of());

        assertEquals(1, invoice.getItems().size());
        assertEquals(Money.of(500_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("Una novedad con fecha posterior al cierre del periodo no altera el consolidado")
    void testUpdateAfterPeriodEndIsIgnored() {
        Invoice invoice = calculate(
                site(RATE_500K, true),
                List.of(update(OrderUpdateType.BAJA, LocalDate.of(2026, 10, 5), Money.ZERO)),
                Map.of());

        assertEquals(Money.of(500_000), invoice.getSubtotalAmount());
    }

    // ── Mes comercial de 30 días: los tramos suman siempre 30, sin importar el mes ──

    private BillingPeriod period(String code, LocalDate start, LocalDate end) {
        return new BillingPeriod(UUID.randomUUID(), code, BillingCutType.ADVANCE, start, end, true);
    }

    private Invoice calculateIn(BillingPeriod period, SubscriptionSite site, List<OrderUpdate> updates,
                                Map<UUID, Money> openingRates) {
        return calculator.calculateConsolidatedInvoice(
                client, period, List.of(site), updates, List.of(), List.of(), openingRates);
    }

    @Test
    @DisplayName("Mes de 31 días, cambio el 16: el incremento cubre 15 días comerciales, no 16")
    void testPlanChangeIn31DayMonthSumsThirtyDays() {
        BillingPeriod august = period("2026-08", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        Invoice invoice = calculateIn(august,
                site(Money.of(800_000), true),
                List.of(update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 8, 16), Money.of(800_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(2, invoice.getItems().size());
        assertTrue(invoice.getItems().get(1).getDescription().contains("GR 16-31 AGT 2026"),
                "La descripción conserva las fechas reales. Fue: " + invoice.getItems().get(1).getDescription());
        assertEquals(Money.of(500_000), invoice.getItems().get(0).getTaxableBase());
        // (800.000 − 500.000) × 15/30; con 16/30 se cobraba un día de más
        assertEquals(Money.of(150_000), invoice.getItems().get(1).getTaxableBase());
        assertEquals(Money.of(650_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("Febrero, cambio el 15: el incremento cubre 16 días comerciales, no 14")
    void testPlanChangeInFebruarySumsThirtyDays() {
        BillingPeriod february = period("2027-02", LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));

        Invoice invoice = calculateIn(february,
                site(Money.of(800_000), true),
                List.of(update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2027, 2, 15), Money.of(800_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(2, invoice.getItems().size());
        assertEquals(Money.of(500_000), invoice.getItems().get(0).getTaxableBase());
        assertEquals(Money.of(160_000), invoice.getItems().get(1).getTaxableBase()); // 300.000 × 16/30
        assertEquals(Money.of(660_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("Cambio efectivo el día 31: el mes se cobra completo al plan anterior y el nuevo arranca el mes siguiente")
    void testPlanChangeOnDay31StartsNextMonth() {
        BillingPeriod august = period("2026-08", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        Invoice invoice = calculateIn(august,
                site(Money.of(800_000), true),
                List.of(update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 8, 31), Money.of(800_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(1, invoice.getItems().size());
        assertEquals(Money.of(500_000), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("Sube el 11 y baja el 21: cargo fijo con descuento + incremento; mismo total que por tramos")
    void testTwoPlanChangesProduceThreeSegments() {
        Invoice invoice = calculate(
                site(Money.of(300_000), true),
                List.of(
                        update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 9, 11), Money.of(800_000)),
                        update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 9, 21), Money.of(300_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(2, invoice.getItems().size());
        InvoiceItem fixed = invoice.getItems().get(0);
        InvoiceItem increment = invoice.getItems().get(1);
        assertTrue(fixed.getDescription().contains("GR 01-30 SEP 2026"));
        assertEquals(Money.of(500_000), fixed.getUnitPrice());
        assertEquals(Money.of(166_667), fixed.getDiscountValue());       // (800.000 − 300.000) × 10/30
        assertTrue(increment.getDescription().contains("GR 11-30 SEP 2026"));
        assertEquals(Money.of(200_000), increment.getTaxableBase());     // (800.000 − 500.000) × 20/30
        // Por tramos: 166.667 + 266.667 + 100.000 = 533.334; la diferencia de un peso es redondeo.
        assertEquals(Money.of(533_333), invoice.getSubtotalAmount());
    }

    @Test
    @DisplayName("Cambio de plan efectivo el día 1: un solo tramo, mes completo al plan nuevo")
    void testPlanChangeOnFirstDayChargesFullMonthAtNewRate() {
        Invoice invoice = calculate(
                site(Money.of(800_000), true),
                List.of(update(OrderUpdateType.CAMBIO_PLAN, LocalDate.of(2026, 9, 1), Money.of(800_000))),
                Map.of(siteId, RATE_500K));

        assertEquals(1, invoice.getItems().size());
        assertEquals(Money.of(800_000), invoice.getItems().get(0).getBaseMonthlyRate());
        assertEquals(Money.of(800_000), invoice.getSubtotalAmount());
    }
}
