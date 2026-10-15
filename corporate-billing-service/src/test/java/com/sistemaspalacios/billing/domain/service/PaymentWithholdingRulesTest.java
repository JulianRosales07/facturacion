package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult;
import com.sistemaspalacios.billing.domain.vo.WithholdingCalculationResult.WithholdingItemEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reglas de liquidación de retenciones al pago (Sprint 9, HU-51/HU-52), casos A–G y CA-1 a CA-4.
 */
class PaymentWithholdingRulesTest {

    private final TaxCalculator calculator = new TaxCalculator();

    private static TaxConceptValidity concept(TaxConceptCode code, String name, String percentage, long minimumBase) {
        return new TaxConceptValidity(UUID.randomUUID(), name, 1, code, null, null,
                Money.of(minimumBase), new BigDecimal(percentage), LocalDate.of(2026, 1, 1), null, true);
    }

    private static WithholdingItemEvaluation only(WithholdingCalculationResult result) {
        assertEquals(1, result.evaluations().size());
        return result.evaluations().getFirst();
    }

    /** totalFacturas = netoAPagar + totalRetenciones - ajusteRedondeo (invariante del dominio). */
    private static void assertBalanced(WithholdingCalculationResult r) {
        assertEquals(r.grossAmount(), r.rawNetPayable().plus(r.totalWithholdings()));
        assertEquals(r.roundedNetPayable(), r.rawNetPayable().plus(r.roundingDifference()));
        assertEquals(r.grossAmount(), r.roundedNetPayable().plus(r.totalWithholdings()).minus(r.roundingDifference()));
    }

    @Test
    @DisplayName("Caso A / CA-1: sin conceptos seleccionados el neto es el total de las facturas")
    void caseA_noWithholdings() {
        var r = calculator.calculatePaymentWithholdings(Money.of(119_000), Money.of(100_000), Money.of(19_000), List.of());
        assertEquals(Money.ZERO, r.totalWithholdings());
        assertEquals(Money.of(119_000), r.roundedNetPayable());
        assertEquals(Money.ZERO, r.roundingDifference());
        assertBalanced(r);
    }

    @Test
    @DisplayName("Caso B / CA-3: ReteFuente 4% aplicable sobre la base gravable")
    void caseB_reteFuenteApplies() {
        var rf = concept(TaxConceptCode.RETEFUENTE, "ReteFuente Servicios 4%", "4.00", 150_000);
        var r = calculator.calculatePaymentWithholdings(Money.of(238_000), Money.of(200_000), Money.of(38_000), List.of(rf));
        var e = only(r);
        assertEquals(Money.of(200_000), e.calculationBase());
        assertTrue(e.applicable());
        assertEquals(Money.of(8_000), e.withheldAmount());
        assertEquals(Money.of(230_000), r.roundedNetPayable());
        assertBalanced(r);
    }

    @Test
    @DisplayName("Caso C / CA-4: ReteFuente no alcanza la base mínima, no aplica y retiene 0")
    void caseC_reteFuenteBelowMinimum() {
        var rf = concept(TaxConceptCode.RETEFUENTE, "ReteFuente Servicios 4%", "4.00", 150_000);
        var r = calculator.calculatePaymentWithholdings(Money.of(119_000), Money.of(100_000), Money.of(19_000), List.of(rf));
        var e = only(r);
        assertEquals(Money.of(100_000), e.calculationBase());
        assertFalse(e.applicable());
        assertEquals(Money.ZERO, e.withheldAmount());
        assertEquals("No alcanza la base mínima de $150.000", e.nonApplicableReason());
        assertEquals(Money.of(119_000), r.roundedNetPayable());
    }

    @Test
    @DisplayName("CA-4: una base igual a la base mínima sí aplica")
    void baseEqualToMinimumApplies() {
        var rf = concept(TaxConceptCode.RETEFUENTE, "ReteFuente 4%", "4.00", 150_000);
        var e = only(calculator.calculatePaymentWithholdings(Money.of(178_500), Money.of(150_000), Money.of(28_500), List.of(rf)));
        assertTrue(e.applicable());
        assertEquals(Money.of(6_000), e.withheldAmount());
    }

    @Test
    @DisplayName("Caso D / CA-2: ReteIVA 15% se calcula sobre el IVA, nunca sobre el total ni la base")
    void caseD_reteIvaOverIvaOnly() {
        var reteIva = concept(TaxConceptCode.RETEIVA, "ReteIVA 15%", "15.00", 0);
        var r = calculator.calculatePaymentWithholdings(Money.of(119_000), Money.of(100_000), Money.of(19_000), List.of(reteIva));
        var e = only(r);
        assertEquals(Money.of(19_000), e.calculationBase());
        assertEquals(Money.of(2_850), e.withheldAmount());
        assertNotEquals(Money.of(17_850), e.withheldAmount(), "No debe calcularse 119.000 × 15%");
        assertNotEquals(Money.of(15_000), e.withheldAmount(), "No debe calcularse 100.000 × 15%");
        assertEquals(Money.of(116_150), r.roundedNetPayable());
        assertBalanced(r);
    }

    @Test
    @DisplayName("Caso D bis: la base mínima de ReteIVA se evalúa contra el IVA")
    void reteIvaMinimumBaseEvaluatedAgainstIva() {
        var reteIva = concept(TaxConceptCode.RETEIVA, "ReteIVA 15%", "15.00", 20_000);
        var e = only(calculator.calculatePaymentWithholdings(Money.of(119_000), Money.of(100_000), Money.of(19_000), List.of(reteIva)));
        assertEquals(Money.of(19_000), e.calculationBase());
        assertFalse(e.applicable(), "19.000 de IVA no alcanza 20.000 aunque la base gravable sí");
    }

    @Test
    @DisplayName("Caso E: varias retenciones (ReteFuente 4%, ReteICA 0,6%, ReteIVA 15%)")
    void caseE_multipleWithholdings() {
        var rf = concept(TaxConceptCode.RETEFUENTE, "ReteFuente 4%", "4.00", 150_000);
        var ica = concept(TaxConceptCode.RETEICA, "ReteICA Pasto 0,6%", "0.60", 0);
        var reteIva = concept(TaxConceptCode.RETEIVA, "ReteIVA 15%", "15.00", 0);
        var r = calculator.calculatePaymentWithholdings(Money.of(595_000), Money.of(500_000), Money.of(95_000), List.of(rf, ica, reteIva));

        assertEquals(Money.of(20_000), r.evaluations().get(0).withheldAmount());
        assertEquals(Money.of(3_000), r.evaluations().get(1).withheldAmount());
        assertEquals(Money.of(14_250), r.evaluations().get(2).withheldAmount());
        assertEquals(Money.of(37_250), r.totalWithholdings());
        assertEquals(Money.of(557_750), r.roundedNetPayable());
        assertBalanced(r);
    }

    @Test
    @DisplayName("CA-3: Estampilla se calcula sobre la base gravable")
    void estampillaOverTaxableBase() {
        var estampilla = concept(TaxConceptCode.ESTAMPILLA, "Estampilla Procultura 1%", "1.00", 0);
        var e = only(calculator.calculatePaymentWithholdings(Money.of(119_000), Money.of(100_000), Money.of(19_000), List.of(estampilla)));
        assertEquals(Money.of(100_000), e.calculationBase());
        assertEquals(Money.of(1_000), e.withheldAmount());
    }

    @Test
    @DisplayName("Caso G: neto 123.456,49 redondea a 123.456 con ajuste -0,49")
    void caseG_roundingDown() {
        var r = calculator.calculatePaymentWithholdings(Money.of(new BigDecimal("123456.49")),
                Money.of(new BigDecimal("123456.49")), Money.ZERO, List.of());
        assertEquals(Money.of(new BigDecimal("123456.49")), r.rawNetPayable());
        assertEquals(Money.of(123_456), r.roundedNetPayable());
        assertEquals(Money.of(new BigDecimal("-0.49")), r.roundingDifference());
        assertBalanced(r);
    }

    @Test
    @DisplayName("Caso G: neto 123.456,50 redondea a 123.457 con ajuste +0,50")
    void caseG_roundingUp() {
        var r = calculator.calculatePaymentWithholdings(Money.of(new BigDecimal("123456.50")),
                Money.of(new BigDecimal("123456.50")), Money.ZERO, List.of());
        assertEquals(Money.of(123_457), r.roundedNetPayable());
        assertEquals(Money.of(new BigDecimal("0.50")), r.roundingDifference());
        assertBalanced(r);
    }

    @Test
    @DisplayName("Caso G con retenciones: el ajuste se expone aparte y el total oficial no cambia")
    void roundingAfterWithholdings() {
        // 100.000,49 × 0,6% = 600,00 → neto 119.000,49 - 600,00 = 118.400,49 → 118.400 (ajuste -0,49)
        var ica = concept(TaxConceptCode.RETEICA, "ReteICA 0,6%", "0.60", 0);
        var r = calculator.calculatePaymentWithholdings(Money.of(new BigDecimal("119000.49")),
                Money.of(new BigDecimal("100000.49")), Money.of(19_000), List.of(ica));
        assertEquals(Money.of(new BigDecimal("119000.49")), r.grossAmount());
        assertEquals(Money.of(600), r.totalWithholdings());
        assertEquals(Money.of(new BigDecimal("118400.49")), r.rawNetPayable());
        assertEquals(Money.of(118_400), r.roundedNetPayable());
        assertEquals(Money.of(new BigDecimal("-0.49")), r.roundingDifference());
        assertBalanced(r);
    }

    @Test
    @DisplayName("El total oficial se toma tal cual aunque base + IVA no lo reconstruyan (ítems excluidos)")
    void officialTotalIsNotRebuiltFromBaseAndIva() {
        // Factura con un ítem excluido de 10.000: base gravable 100.000, IVA 19.000, total oficial 129.000
        var rf = concept(TaxConceptCode.RETEFUENTE, "ReteFuente 4%", "4.00", 0);
        var r = calculator.calculatePaymentWithholdings(Money.of(129_000), Money.of(100_000), Money.of(19_000), List.of(rf));
        assertEquals(Money.of(129_000), r.grossAmount());
        assertEquals(Money.of(4_000), r.totalWithholdings());
        assertEquals(Money.of(125_000), r.roundedNetPayable());
    }
}
