package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BillingCalculatorTest {

    private BillingCalculator calculator;
    private BillingPeriod septemberPeriod;
    private CorporateClient client;

    @BeforeEach
    void setUp() {
        calculator = new BillingCalculator();
        septemberPeriod = new BillingPeriod(
                UUID.randomUUID(),
                "2026-09",
                BillingCutType.ADVANCE,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30),
                true
        );

        client = new CorporateClient(
                UUID.randomUUID(),
                "900245045",
                "CLIENTE CORP SAS",
                "Calle 100",
                "factura@corp.com",
                "3101234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
    }

    @Test
    @DisplayName("HU-10: Effective charge date uses fecha_certificacion unless observation overrides it")
    void testEffectiveChargeDateResolution() {
        SubscriptionSite site = new SubscriptionSite(
                UUID.randomUUID(),
                client.getId(),
                "ORD-001",
                OrderOrigin.SPE,
                "NIC-101",
                "SEDE NORTE",
                "Cra 1",
                Money.of(1_000_000),
                LocalDate.of(2026, 8, 20), // certification date
                true
        );

        // Without update observation -> uses site.certificationDate
        LocalDate resolvedDate1 = calculator.resolveEffectiveBillingDate(site, null, septemberPeriod.getStartDate());
        assertEquals(LocalDate.of(2026, 8, 20), resolvedDate1);

        // With update observation overriding date
        OrderUpdate update = new OrderUpdate(
                UUID.randomUUID(),
                site.getId(),
                OrderUpdateType.ALTA,
                septemberPeriod.getId(),
                Money.of(1_000_000),
                LocalDate.of(2026, 9, 10),
                true,
                "Cobro comercial a partir de 2026-09-15 por acuerdo"
        );
        LocalDate resolvedDate2 = calculator.resolveEffectiveBillingDate(site, update, septemberPeriod.getStartDate());
        assertEquals(LocalDate.of(2026, 9, 15), resolvedDate2);
    }

    @Test
    @DisplayName("HU-18, Sprint 9: Line description follows real Siigo patterns and AGT month abbreviation")
    void testFormatItemDescription() {
        DateRange augustRangeFull = new DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        DateRange augustRangeProrated = new DateRange(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 31));

        String desc1 = calculator.formatItemDescription("LA CRUZ", "INT.FIB.DEDI.1.65 GB", augustRangeFull);
        assertEquals("LA CRUZ.INT.FIB.DEDI.1.65 GB GR 01-31 AGT 2026", desc1);

        String desc2 = calculator.formatItemDescription("PLAZUELAS", " INT.FIB.DEDI 200MB", augustRangeProrated);
        assertEquals("PLAZUELAS INT.FIB.DEDI 200MB GR 12-31 AGT 2026", desc2);

        String desc3 = calculator.formatItemDescription(null, "NT.FIB.CORP.200MB", augustRangeFull);
        assertEquals("NT.FIB.CORP.200MB GR 01-31 AGT 2026", desc3);
    }

    @Test
    @DisplayName("Canonical Acceptance Slice: 3 sites (1 full, 1 mid-period ALTA, 1 with installation fee)")
    void testConsolidatedInvoiceThreeSites() {
        // Site 1: Active full month ($1,000,000)
        SubscriptionSite site1 = new SubscriptionSite(
                UUID.randomUUID(), client.getId(), "ORD-001", OrderOrigin.SPE, "NIC-01",
                "SEDE CENTRO", "Dir 1", Money.of(1_000_000), LocalDate.of(2026, 8, 1), true
        );

        // Site 2: ALTA mid-period on Sept 16 ($1,200,000 monthly -> $600,000 prorated for 15 days)
        SubscriptionSite site2 = new SubscriptionSite(
                UUID.randomUUID(), client.getId(), "ORD-002", OrderOrigin.SPE, "NIC-02",
                "SEDE NORTE", "Dir 2", Money.of(1_200_000), LocalDate.of(2026, 9, 16), true
        );

        // Site 3: Active full month ($800,000) + Installation fee ($200,000 installment)
        SubscriptionSite site3 = new SubscriptionSite(
                UUID.randomUUID(), client.getId(), "ORD-003", OrderOrigin.SPE, "NIC-03",
                "SEDE SUR", "Dir 3", Money.of(800_000), LocalDate.of(2026, 8, 1), true
        );

        InstallationFee feeSite3 = new InstallationFee(
                UUID.randomUUID(), site3.getId(), Money.of(1_200_000), 6, 2, Money.of(200_000), InstallationFeeStatus.BILLING
        );

        Invoice invoice = calculator.calculateConsolidatedInvoice(
                client,
                septemberPeriod,
                List.of(site1, site2, site3),
                List.of(),
                List.of(feeSite3),
                List.of()
        );

        // 4 items: 3 service items + 1 installation fee installment
        assertEquals(4, invoice.getItems().size());
        assertEquals(3, feeSite3.getBilledInstallments()); // Incremented from 2 to 3

        // Expected subtotal:
        // site1: 1,000,000
        // site2: 600,000 (prorated 15/30)
        // site3: 800,000
        // feeSite3: 200,000
        // Total subtotal = 2,600,000
        assertEquals(Money.of(2_600_000), invoice.getSubtotalAmount());

        // IVA 19% on 2,600,000 = 494,000
        assertEquals(Money.of(494_000), invoice.getIvaAmount());

        // Official invoice total = 2,600,000 + 494,000 = 3,094,000
        assertEquals(Money.of(3_094_000), invoice.getTotalAmount());
        assertEquals(invoice.getTotalAmount(), invoice.getNetPayableAmount()); // No withholdings for this client

        // Invariant: client fiscal snapshot is captured
        assertEquals(client.getNit(), invoice.getClientNit());
        assertEquals(client.getBusinessName(), invoice.getClientBusinessName());
    }

    @Test
    @DisplayName("HU-13: Item discount correctly reduces taxable base and IVA")
    void testItemDiscount() {
        InvoiceItem item = new InvoiceItem(
                null,
                UUID.randomUUID(),
                BillableItemType.CORPORATE_SERVICE,
                UUID.randomUUID(),
                "INTERNET CON DESCUENTO",
                BigDecimal.ONE,
                Money.of(1_000_000),
                BigDecimal.valueOf(10), // 10% discount
                true,
                BigDecimal.valueOf(19)
        );

        assertEquals(Money.of(100_000), item.getDiscountValue());
        assertEquals(Money.of(900_000), item.getTaxableBase());
        assertEquals(Money.of(171_000), item.getIvaValue()); // 900,000 * 19%
        assertEquals(Money.of(1_071_000), item.getTotalValue());
    }
}
