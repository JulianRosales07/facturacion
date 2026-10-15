package com.sistemaspalacios.billing.domain.vo;

import com.sistemaspalacios.billing.domain.model.AccountingLineType;
import com.sistemaspalacios.billing.domain.model.AccountingNature;

import java.util.Objects;

/**
 * Line of a double-entry balanced accounting entry for Siigo export and auditing.
 */
public record AccountingEntryLine(
        int sequence,
        AccountingLineType lineType,
        String accountCode,
        AccountingNature nature,
        Money debit,
        Money credit,
        String description
) {
    public AccountingEntryLine {
        Objects.requireNonNull(lineType, "lineType cannot be null");
        Objects.requireNonNull(accountCode, "accountCode cannot be null");
        Objects.requireNonNull(nature, "nature cannot be null");
        Objects.requireNonNull(debit, "debit cannot be null");
        Objects.requireNonNull(credit, "credit cannot be null");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be greater than 0");
        }
    }

    public static AccountingEntryLine debit(int sequence, AccountingLineType lineType, String accountCode, Money amount, String description) {
        return new AccountingEntryLine(sequence, lineType, accountCode, AccountingNature.D, amount, Money.ZERO, description);
    }

    public static AccountingEntryLine credit(int sequence, AccountingLineType lineType, String accountCode, Money amount, String description) {
        return new AccountingEntryLine(sequence, lineType, accountCode, AccountingNature.C, Money.ZERO, amount, description);
    }
}
