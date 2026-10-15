package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.ProrationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ProrationCalculatorTest {

    private ProrationCalculator calculator;
    private DateRange septemberPeriod;

    @BeforeEach
    void setUp() {
        calculator = new ProrationCalculator();
        septemberPeriod = new DateRange(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30)
        );
    }

    @Test
    @DisplayName("HU-11: Full month charge when service is certified on or before period start")
    void testFullMonthCharge() {
        Money monthlyValue = Money.of(1_200_000);
        LocalDate effectiveDate = LocalDate.of(2026, 8, 15);

        ProrationResult result = calculator.calculate(
                monthlyValue,
                effectiveDate,
                septemberPeriod,
                BillingCutType.ADVANCE,
                false
        );

        assertEquals(30, result.baseDays());
        assertEquals(30, result.effectiveDays());
        assertEquals(BigDecimal.ONE, result.factor());
        assertEquals(monthlyValue, result.proratedAmount());
    }

    @Test
    @DisplayName("HU-11: Prorated charge for ALTA mid-period (effective Sept 16 -> 15 days)")
    void testProratedChargeMidMonth() {
        Money monthlyValue = Money.of(1_200_000);
        LocalDate effectiveDate = LocalDate.of(2026, 9, 16); // 16 to 30 = 15 days

        ProrationResult result = calculator.calculate(
                monthlyValue,
                effectiveDate,
                septemberPeriod,
                BillingCutType.ADVANCE,
                false
        );

        assertEquals(30, result.baseDays());
        assertEquals(15, result.effectiveDays());
        // 15 / 30 = 0.50 -> 600,000
        assertEquals(Money.of(600_000), result.proratedAmount());
    }

    @Test
    @DisplayName("HU-11: Commercial observation waives proration (charges full month)")
    void testWaivedProration() {
        Money monthlyValue = Money.of(900_000);
        LocalDate effectiveDate = LocalDate.of(2026, 9, 20);

        ProrationResult result = calculator.calculate(
                monthlyValue,
                effectiveDate,
                septemberPeriod,
                BillingCutType.ADVANCE,
                true // commercial observation waived proration
        );

        assertEquals(30, result.effectiveDays());
        assertEquals(monthlyValue, result.proratedAmount());
    }

    @Test
    @DisplayName("HU-11: Effective date after period end yields zero charge")
    void testEffectiveDateAfterPeriodEnd() {
        Money monthlyValue = Money.of(1_000_000);
        LocalDate effectiveDate = LocalDate.of(2026, 10, 5);

        ProrationResult result = calculator.calculate(
                monthlyValue,
                effectiveDate,
                septemberPeriod,
                BillingCutType.ADVANCE,
                false
        );

        assertEquals(0, result.effectiveDays());
        assertEquals(Money.ZERO, result.proratedAmount());
    }

    @Test
    @DisplayName("Mes comercial: los tramos de cualquier mes suman 30 días")
    void testCommercialDaysAlwaysSumThirty() {
        // Mes de 31 días partido el 16
        assertEquals(15, ProrationCalculator.commercialDays(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)));
        assertEquals(15, ProrationCalculator.commercialDays(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 31)));
        // Febrero partido el 15
        assertEquals(14, ProrationCalculator.commercialDays(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 14)));
        assertEquals(16, ProrationCalculator.commercialDays(LocalDate.of(2027, 2, 15), LocalDate.of(2027, 2, 28)));
        // Febrero bisiesto partido el 15
        assertEquals(16, ProrationCalculator.commercialDays(LocalDate.of(2028, 2, 15), LocalDate.of(2028, 2, 29)));
        // El día 31 por sí solo no cuenta
        assertEquals(0, ProrationCalculator.commercialDays(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)));
        // Tramo intermedio en mes de 30 días
        assertEquals(15, ProrationCalculator.commercialDays(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 20)));
    }

    @Test
    @DisplayName("ALTA el día 31 de un mes de 31 días: cero días, se cobra desde el mes siguiente")
    void testAltaOnDay31ChargesNothing() {
        DateRange august = new DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        ProrationResult result = calculator.calculate(
                Money.of(1_000_000), LocalDate.of(2026, 8, 31), august, BillingCutType.ADVANCE, false);

        assertEquals(0, result.effectiveDays());
        assertEquals(Money.ZERO, result.proratedAmount());
    }

    @Test
    @DisplayName("El tramo se calcula sobre valor × días / 30, sin perder pesos por redondear el factor")
    void testSegmentAmountRoundsToPesoWithoutFactorDrift() {
        DateRange february = new DateRange(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));

        ProrationResult result = calculator.calculateSegment(
                Money.of(15_000_000), LocalDate.of(2027, 2, 15), null, february);

        assertEquals(16, result.effectiveDays());
        // Con factor 0,533333 daba 7.999.995
        assertEquals(Money.of(8_000_000), result.proratedAmount());
    }
}
