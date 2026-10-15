package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import com.sistemaspalacios.billing.domain.vo.WithholdingDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaxCalculatorTest {

    private TaxCalculator calculator;
    private LocalDate cutoffDate;

    @BeforeEach
    void setUp() {
        calculator = new TaxCalculator();
        cutoffDate = LocalDate.of(2026, 9, 30);
    }

    @Test
    @DisplayName("HU-14: IVA 19% on taxed item and 0% on non-taxed item")
    void testIvaCalculation() {
        Money base = Money.of(1_000_000);

        Money ivaTaxed = calculator.calculateIva(base, true, BigDecimal.valueOf(19));
        assertEquals(Money.of(190_000), ivaTaxed);

        Money ivaUntaxed = calculator.calculateIva(base, false, BigDecimal.valueOf(19));
        assertEquals(Money.ZERO, ivaUntaxed);
    }

    @Test
    @DisplayName("HU-15: Client with appliesWithholding=false has zero withholdings")
    void testClientWithoutWithholding() {
        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                "900123456",
                "CLIENTE SIN RETENCION SAS",
                "Calle 1",
                "factura@cliente.com",
                "3001234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false, // no withholdings
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );

        List<WithholdingDetail> withholdings = calculator.calculateWithholdings(
                client,
                Money.of(5_000_000),
                Money.of(950_000),
                cutoffDate,
                List.of()
        );

        assertTrue(withholdings.isEmpty());
    }

    @Test
    @DisplayName("HU-15: Full withholdings calculation (Retefuente, ReteICA territorial, ReteIVA)")
    void testFullWithholdings() {
        // Setup client with Retefuente, ReteICA (Pasto) and ReteIVA
        TaxProfile taxProfile = new TaxProfile(
                true,
                "Pasto",
                List.of(
                        new TaxProfile.TaxProfileItem(TaxConceptCode.RETEFUENTE, ServiceClassification.INTERNET, null),
                        new TaxProfile.TaxProfileItem(TaxConceptCode.RETEICA, ServiceClassification.INTERNET, null),
                        new TaxProfile.TaxProfileItem(TaxConceptCode.RETEIVA, null, null)
                )
        );

        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                "900245045",
                "CORPORATIVO PASTO SAS",
                "Cra 20",
                "contabilidad@corp.com",
                "7234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                true,
                "Pasto",
                taxProfile
        );

        // Validities: Retefuente 3.5% (base min 100k), ReteICA Pasto 0.966% (base min 0), ReteIVA 15% on IVA
        List<TaxConceptValidity> validities = List.of(
                new TaxConceptValidity(
                        UUID.randomUUID(),
                        TaxConceptCode.RETEFUENTE,
                        ServiceClassification.INTERNET,
                        null,
                        Money.of(100_000),
                        new BigDecimal("3.5"),
                        LocalDate.of(2026, 1, 1),
                        null,
                        true
                ),
                new TaxConceptValidity(
                        UUID.randomUUID(),
                        TaxConceptCode.RETEICA,
                        ServiceClassification.INTERNET,
                        "Pasto",
                        Money.ZERO,
                        new BigDecimal("0.966"),
                        LocalDate.of(2026, 1, 1),
                        null,
                        true
                ),
                new TaxConceptValidity(
                        UUID.randomUUID(),
                        TaxConceptCode.RETEIVA,
                        null,
                        null,
                        Money.ZERO,
                        BigDecimal.valueOf(15), // 15% of IVA
                        LocalDate.of(2026, 1, 1),
                        null,
                        true
                )
        );

        Money subtotal = Money.of(10_000_000);
        Money iva = Money.of(1_900_000);

        List<WithholdingDetail> details = calculator.calculateWithholdings(
                client,
                subtotal,
                iva,
                cutoffDate,
                validities
        );

        assertEquals(3, details.size());

        // Retefuente: 10,000,000 * 3.5% = 350,000
        WithholdingDetail rf = details.stream().filter(d -> d.conceptCode() == TaxConceptCode.RETEFUENTE).findFirst().orElseThrow();
        assertEquals(Money.of(350_000), rf.withheldAmount());

        // ReteICA: 10,000,000 * 0.966% = 96,600
        WithholdingDetail rIca = details.stream().filter(d -> d.conceptCode() == TaxConceptCode.RETEICA).findFirst().orElseThrow();
        assertEquals(Money.of(96_600), rIca.withheldAmount());

        // ReteIVA: 1,900,000 * 15% = 285,000
        WithholdingDetail rIva = details.stream().filter(d -> d.conceptCode() == TaxConceptCode.RETEIVA).findFirst().orElseThrow();
        assertEquals(Money.of(285_000), rIva.withheldAmount());
    }

    @Test
    @DisplayName("HU-15: Territorial ReteICA is skipped when client municipality does not match validity")
    void testReteicaTerritorialMismatch() {
        TaxProfile taxProfile = new TaxProfile(
                true,
                "Ipiales", // Different municipality!
                List.of(new TaxProfile.TaxProfileItem(TaxConceptCode.RETEICA, ServiceClassification.INTERNET, null))
        );

        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                "900999888",
                "CLIENTE IPIALES SAS",
                "Calle Real",
                "ipiales@corp.com",
                "777777",
                (short) 1,
                (short) 1,
                BillingType.F1,
                true,
                "Ipiales",
                taxProfile
        );

        // Validity is strictly for Pasto
        List<TaxConceptValidity> validities = List.of(
                new TaxConceptValidity(
                        UUID.randomUUID(),
                        TaxConceptCode.RETEICA,
                        ServiceClassification.INTERNET,
                        "Pasto",
                        Money.ZERO,
                        new BigDecimal("0.966"),
                        LocalDate.of(2026, 1, 1),
                        null,
                        true
                )
        );

        List<WithholdingDetail> details = calculator.calculateWithholdings(
                client,
                Money.of(1_000_000),
                Money.of(190_000),
                cutoffDate,
                validities
        );

        assertTrue(details.isEmpty(), "ReteICA should not apply when municipality does not match");
    }

    @Test
    @DisplayName("HU-15: Agreed percentage override takes precedence over statutory validity rate")
    void testAgreedPercentageOverride() {
        TaxProfile taxProfile = new TaxProfile(
                true,
                "Pasto",
                List.of(new TaxProfile.TaxProfileItem(TaxConceptCode.RETEFUENTE, ServiceClassification.INTERNET, new BigDecimal("4.0"))) // Override to 4.0%
        );

        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                "900555444",
                "CLIENTE CONVENIO SAS",
                "Av 1",
                "convenio@corp.com",
                "888888",
                (short) 1,
                (short) 1,
                BillingType.F1,
                true,
                "Pasto",
                taxProfile
        );

        List<TaxConceptValidity> validities = List.of(
                new TaxConceptValidity(
                        UUID.randomUUID(),
                        TaxConceptCode.RETEFUENTE,
                        ServiceClassification.INTERNET,
                        null,
                        Money.ZERO,
                        new BigDecimal("3.5"), // Statutory rate is 3.5%
                        LocalDate.of(2026, 1, 1),
                        null,
                        true
                )
        );

        List<WithholdingDetail> details = calculator.calculateWithholdings(
                client,
                Money.of(1_000_000),
                Money.of(190_000),
                cutoffDate,
                validities
        );

        assertEquals(1, details.size());
        assertEquals(new BigDecimal("4.0"), details.get(0).ratePercentage());
        assertEquals(Money.of(40_000), details.get(0).withheldAmount()); // 1,000,000 * 4.0% = 40,000
    }

    @Test
    @DisplayName("HU-51: Simulación/Liquidación de pago con selección vacía -> neto = total factura")
    void testPaymentWithholdingsEmptySelection() {
        Money subtotal = Money.of(1_000_000);
        Money iva = Money.of(190_000);

        com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult result =
                calculator.calculatePaymentWithholdings(subtotal, iva, List.of());

        assertEquals(Money.of(1_190_000), result.grossAmount());
        assertEquals(Money.ZERO, result.totalWithholdings());
        assertEquals(Money.of(1_190_000), result.rawNetPayable());
        assertEquals(Money.of(1_190_000), result.roundedNetPayable());
        assertEquals(Money.ZERO, result.roundingDifference());
        assertTrue(result.evaluations().isEmpty());
    }

    @Test
    @DisplayName("HU-52: ReteIVA calculado estrictamente sobre el valor del IVA y no sobre el total ni subtotal")
    void testPaymentWithholdingReteIvaCalculatedStrictlyOverIva() {
        Money subtotal = Money.of(10_000_000);
        Money iva = Money.of(1_900_000); // IVA 19%

        TaxConceptValidity reteIva15 = new TaxConceptValidity(
                UUID.randomUUID(),
                "ReteIVA 15%",
                1,
                TaxConceptCode.RETEIVA,
                ServiceClassification.INTERNET,
                null,
                Money.ZERO,
                BigDecimal.valueOf(15.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult result =
                calculator.calculatePaymentWithholdings(subtotal, iva, List.of(reteIva15));

        assertEquals(1, result.evaluations().size());
        com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation eval = result.evaluations().get(0);
        assertTrue(eval.applicable());
        assertEquals(iva, eval.calculationBase(), "La base de cálculo de ReteIVA debe ser exactamente el IVA");
        assertEquals(Money.of(285_000), eval.withheldAmount(), "1,900,000 * 15% = 285,000");

        // Total retenciones = 285,000
        assertEquals(Money.of(285_000), result.totalWithholdings());
        // Neto = 11,900,000 - 285,000 = 11,615,000
        assertEquals(Money.of(11_615_000), result.rawNetPayable());
    }

    @Test
    @DisplayName("HU-52: Concepto por debajo de base mínima se marca no aplicable y no descuenta")
    void testPaymentWithholdingBaseMinimaThreshold() {
        Money subtotal = Money.of(800_000); // Debajo de 1,000,000
        Money iva = Money.of(152_000);

        TaxConceptValidity rfConBaseMinima = new TaxConceptValidity(
                UUID.randomUUID(),
                "ReteFuente 4% con base mínima 1M",
                1,
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(1_000_000),
                BigDecimal.valueOf(4.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult result =
                calculator.calculatePaymentWithholdings(subtotal, iva, List.of(rfConBaseMinima));

        assertEquals(1, result.evaluations().size());
        com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation eval = result.evaluations().get(0);
        assertFalse(eval.applicable());
        assertEquals(Money.ZERO, eval.withheldAmount());
        assertNotNull(eval.nonApplicableReason());
        assertEquals("No alcanza la base mínima de $1.000.000", eval.nonApplicableReason());

        assertEquals(Money.ZERO, result.totalWithholdings());
        assertEquals(Money.of(952_000), result.rawNetPayable());
    }

    @Test
    @DisplayName("HU-52: Redondeo al peso HALF_UP con diferencia por redondeo expuesta")
    void testPaymentWithholdingRoundingAndDifference() {
        // Subtotal con centavos
        Money subtotal = Money.of(new BigDecimal("100000.45"));
        Money iva = Money.of(new BigDecimal("19000.08"));
        // Total = 119,000.53

        com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult result =
                calculator.calculatePaymentWithholdings(subtotal, iva, List.of());

        assertEquals(Money.of(new BigDecimal("119000.53")), result.rawNetPayable());
        assertEquals(Money.of(new BigDecimal("119001.00")), result.roundedNetPayable());
        assertEquals(Money.of(new BigDecimal("0.47")), result.roundingDifference());
    }
}

