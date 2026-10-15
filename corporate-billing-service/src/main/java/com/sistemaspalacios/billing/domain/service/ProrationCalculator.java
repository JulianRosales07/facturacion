package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.vo.DateRange;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.ProrationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pure domain service for calculating proration of monthly charges (HU-11).
 * Standard commercial base is 30 days.
 */
public class ProrationCalculator {

    public static final int COMMERCIAL_MONTH_DAYS = 30;
    private static final int CALCULATION_SCALE = 6;

    /**
     * Calculates proration from effectiveDate until the end of the billing period.
     *
     * @param monthlyValue Full monthly charge
     * @param effectiveDate Start date of the service or plan change within the period
     * @param periodRange Date range of the billing period
     * @param cutType ADVANCE or ARREARS
     * @param waiveProration If true, waives proration (returns full month)
     * @return ProrationResult with days, factor and prorated amount
     */
    public ProrationResult calculate(
            Money monthlyValue,
            LocalDate effectiveDate,
            DateRange periodRange,
            BillingCutType cutType,
            boolean waiveProration
    ) {
        Objects.requireNonNull(monthlyValue, "monthlyValue cannot be null");
        Objects.requireNonNull(periodRange, "periodRange cannot be null");

        if (waiveProration || effectiveDate == null) {
            return new ProrationResult(COMMERCIAL_MONTH_DAYS, COMMERCIAL_MONTH_DAYS, BigDecimal.ONE, monthlyValue);
        }

        // If effective date is before or equal to period start, full month applies
        if (!effectiveDate.isAfter(periodRange.startDate())) {
            return new ProrationResult(COMMERCIAL_MONTH_DAYS, COMMERCIAL_MONTH_DAYS, BigDecimal.ONE, monthlyValue);
        }

        // If effective date is after period end, 0 days
        if (effectiveDate.isAfter(periodRange.endDate())) {
            return new ProrationResult(COMMERCIAL_MONTH_DAYS, 0, BigDecimal.ZERO, Money.ZERO);
        }

        return prorated(monthlyValue, commercialDays(effectiveDate, periodRange.endDate()));
    }

    /**
     * Prorratea el cargo correspondiente a un segmento arbitrario dentro del periodo
     * (Sprint 10).
     * <p>
     * {@link #calculate} solo sabe cobrar desde una fecha hasta el fin del periodo, lo
     * que sirve para altas y reactivaciones. Las bajas, suspensiones y cambios de plan
     * necesitan cobrar un tramo intermedio o un tramo que termina antes del cierre, y
     * ese es el caso que cubre este método.
     * <p>
     * Ambos extremos son inclusivos y se recortan al periodo. Si el segmento queda vacío
     * (por ejemplo una baja efectiva el mismo día en que arranca el periodo) el resultado
     * es cero días y monto cero.
     *
     * @param monthlyValue tarifa mensual completa aplicable al segmento
     * @param segmentStart inicio del cobro, inclusive; {@code null} = inicio del periodo
     * @param segmentEnd   fin del cobro, inclusive; {@code null} = fin del periodo
     * @param periodRange  rango del periodo de facturación
     */
    public ProrationResult calculateSegment(
            Money monthlyValue,
            LocalDate segmentStart,
            LocalDate segmentEnd,
            DateRange periodRange
    ) {
        Objects.requireNonNull(monthlyValue, "monthlyValue cannot be null");
        Objects.requireNonNull(periodRange, "periodRange cannot be null");

        LocalDate start = (segmentStart == null || segmentStart.isBefore(periodRange.startDate()))
                ? periodRange.startDate()
                : segmentStart;
        LocalDate end = (segmentEnd == null || segmentEnd.isAfter(periodRange.endDate()))
                ? periodRange.endDate()
                : segmentEnd;

        if (end.isBefore(start)) {
            return new ProrationResult(COMMERCIAL_MONTH_DAYS, 0, BigDecimal.ZERO, Money.ZERO);
        }

        // Un segmento que cubre el periodo completo se cobra como mes comercial completo,
        // sin importar que el mes calendario tenga 28 o 31 días.
        if (!start.isAfter(periodRange.startDate()) && !end.isBefore(periodRange.endDate())) {
            return new ProrationResult(COMMERCIAL_MONTH_DAYS, COMMERCIAL_MONTH_DAYS, BigDecimal.ONE, monthlyValue);
        }

        return prorated(monthlyValue, commercialDays(start, end));
    }

    /**
     * Días comerciales del tramo {@code [start, end]}, ambos inclusive y dentro de un mismo
     * mes calendario.
     * <p>
     * En el mes comercial todo mes tiene 30 días: el día 31 no cuenta y el último día de
     * febrero completa hasta 30. Así los tramos de un periodo suman siempre 30 sin importar
     * dónde se parta: en un mes de 31 días con cambio el 16 salen 15 + 15 (no 15 + 16), y en
     * febrero con cambio el 15 salen 14 + 16 (no 14 + 14). Una novedad efectiva el día 31
     * deja el tramo nuevo en cero días: el plan nuevo arranca el mes siguiente.
     */
    public static int commercialDays(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        int daysBefore = start.getDayOfMonth() == 1 ? 0 : commercialDayOf(start.minusDays(1));
        return Math.max(0, commercialDayOf(end) - daysBefore);
    }

    /** Posición de la fecha en el mes comercial (1..30): el último día del mes es siempre el 30. */
    private static int commercialDayOf(LocalDate date) {
        if (date.getDayOfMonth() == date.lengthOfMonth()) {
            return COMMERCIAL_MONTH_DAYS;
        }
        return Math.min(date.getDayOfMonth(), COMMERCIAL_MONTH_DAYS);
    }

    /**
     * {@code valor_mensual × días / 30}, redondeado al peso (HALF_UP). Se divide el producto
     * y no se multiplica por un factor ya redondeado, para que 16/30 no pierda pesos
     * (15.000.000 × 0,533333 daría 7.999.995 en lugar de 8.000.000).
     */
    private ProrationResult prorated(Money monthlyValue, int days) {
        if (days <= 0) {
            return new ProrationResult(COMMERCIAL_MONTH_DAYS, 0, BigDecimal.ZERO, Money.ZERO);
        }
        BigDecimal factor = BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(COMMERCIAL_MONTH_DAYS), CALCULATION_SCALE, RoundingMode.HALF_UP);
        Money amount = monthlyValue.multiply(days).divide(COMMERCIAL_MONTH_DAYS).roundToPeso();
        return new ProrationResult(COMMERCIAL_MONTH_DAYS, days, factor, amount);
    }
}
