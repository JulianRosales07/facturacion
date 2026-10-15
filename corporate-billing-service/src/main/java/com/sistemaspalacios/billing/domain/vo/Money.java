package com.sistemaspalacios.billing.domain.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value Object encapsulating monetary values in COP.
 * Guarantees that no floating point arithmetic is used and enforces RoundingMode.HALF_UP.
 */
public final class Money implements Comparable<Money> {

    private static final int DEFAULT_SCALE = 2;
    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static Money of(BigDecimal amount) {
        if (amount == null) {
            return ZERO;
        }
        return new Money(amount);
    }

    public static Money of(long amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public static Money of(String amount) {
        if (amount == null || amount.isBlank()) {
            return ZERO;
        }
        return new Money(new BigDecimal(amount));
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other Money must not be null");
        return new Money(this.amount.add(other.amount));
    }

    public Money add(Money other) {
        return plus(other);
    }

    public Money minus(Money other) {
        Objects.requireNonNull(other, "other Money must not be null");
        return new Money(this.amount.subtract(other.amount));
    }

    public Money subtract(Money other) {
        return minus(other);
    }

    public Money roundToPeso() {
        return new Money(this.amount.setScale(0, RoundingMode.HALF_UP).setScale(DEFAULT_SCALE));
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");
        return new Money(this.amount.multiply(factor));
    }

    public Money multiply(long factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
    }

    public Money divide(long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("Division by zero");
        }
        BigDecimal result = this.amount.divide(BigDecimal.valueOf(divisor), DEFAULT_SCALE, DEFAULT_ROUNDING);
        return new Money(result);
    }

    /**
     * Calculates percentage: amount * (percentage / 100)
     */
    public Money percentage(BigDecimal percentage) {
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        BigDecimal result = this.amount.multiply(percentage)
                .divide(BigDecimal.valueOf(100), DEFAULT_SCALE, DEFAULT_ROUNDING);
        return new Money(result);
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return this.compareTo(other) >= 0;
    }

    public boolean isGreaterThan(Money other) {
        return this.compareTo(other) > 0;
    }

    @Override
    public int compareTo(Money o) {
        Objects.requireNonNull(o, "Money comparison requires non-null argument");
        return this.amount.compareTo(o.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
