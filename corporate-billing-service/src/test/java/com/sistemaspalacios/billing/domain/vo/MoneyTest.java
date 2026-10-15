package com.sistemaspalacios.billing.domain.vo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    @DisplayName("Money enforces scale 2 and RoundingMode.HALF_UP")
    void testScaleAndRounding() {
        Money m1 = Money.of(new BigDecimal("100.555"));
        assertEquals(new BigDecimal("100.56"), m1.getAmount());

        Money m2 = Money.of(new BigDecimal("100.554"));
        assertEquals(new BigDecimal("100.55"), m2.getAmount());
    }

    @Test
    @DisplayName("Money addition, subtraction and multiplication")
    void testArithmetic() {
        Money m1 = Money.of(100_000);
        Money m2 = Money.of(50_000);

        assertEquals(Money.of(150_000), m1.plus(m2));
        assertEquals(Money.of(50_000), m1.minus(m2));
        assertEquals(Money.of(200_000), m1.multiply(2));
    }

    @Test
    @DisplayName("Percentage calculation with rounding")
    void testPercentage() {
        Money base = Money.of(1_000_000);
        Money iva = base.percentage(BigDecimal.valueOf(19));
        assertEquals(Money.of(190_000), iva);

        Money retefuente = base.percentage(new BigDecimal("3.5"));
        assertEquals(Money.of(35_000), retefuente);
    }

    @Test
    @DisplayName("Comparison and predicate checks")
    void testComparison() {
        Money m1 = Money.of(100);
        Money m2 = Money.of(200);

        assertTrue(m2.isGreaterThanOrEqual(m1));
        assertFalse(m1.isGreaterThanOrEqual(m2));
        assertTrue(m1.isPositive());
        assertTrue(Money.ZERO.isZero());
        assertFalse(m1.isZero());
    }
}
