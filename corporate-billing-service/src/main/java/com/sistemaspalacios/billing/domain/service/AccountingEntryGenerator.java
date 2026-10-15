package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.AccountingLineType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.vo.AccountingEntryLine;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure domain service for generating balanced double-entry accounting movements (HU-16).
 * Critical Invariant: The accounting entry does NOT include withholdings (Rule 9).
 * Total debits must strictly equal total credits.
 */
public class AccountingEntryGenerator {

    public static final String DEFAULT_REVENUE_ACCOUNT = "4105011501";
    public static final String DEFAULT_IVA_ACCOUNT = "2408010515";
    public static final String DEFAULT_RECEIVABLES_ACCOUNT = "1305051004";

    /**
     * Generates a balanced accounting voucher for a consolidated invoice.
     *
     * @param invoice The consolidated invoice
     * @param accountMappings Optional account code mappings (revenue, iva, receivables)
     * @return List of accounting lines strictly balanced (debits == credits)
     * @throws UnbalancedAccountingEntryException if debits do not equal credits
     */
    public List<AccountingEntryLine> generateEntry(Invoice invoice, Map<AccountingLineType, String> accountMappings) {
        Objects.requireNonNull(invoice, "invoice cannot be null");
        invoice.assertCanBeIssued();

        String revenueAccount = (accountMappings != null && accountMappings.containsKey(AccountingLineType.INGRESO))
                ? accountMappings.get(AccountingLineType.INGRESO)
                : DEFAULT_REVENUE_ACCOUNT;

        String ivaAccount = (accountMappings != null && accountMappings.containsKey(AccountingLineType.IVA))
                ? accountMappings.get(AccountingLineType.IVA)
                : DEFAULT_IVA_ACCOUNT;

        String receivablesAccount = (accountMappings != null && accountMappings.containsKey(AccountingLineType.CARTERA))
                ? accountMappings.get(AccountingLineType.CARTERA)
                : DEFAULT_RECEIVABLES_ACCOUNT;

        List<AccountingEntryLine> lines = new ArrayList<>();
        int sequence = 1;

        // 1. N CREDIT lines to Revenue (one per billable line item)
        for (InvoiceItem item : invoice.getItems()) {
            if (item.getTaxableBase().isPositive()) {
                lines.add(AccountingEntryLine.credit(
                        sequence++,
                        AccountingLineType.INGRESO,
                        revenueAccount,
                        item.getTaxableBase(),
                        item.getDescription()
                ));
            }
        }

        // 2. 1 CREDIT line to IVA (if IVA > 0)
        if (invoice.getIvaAmount().isPositive()) {
            lines.add(AccountingEntryLine.credit(
                    sequence++,
                    AccountingLineType.IVA,
                    ivaAccount,
                    invoice.getIvaAmount(),
                    "IVA GENERADO 19% FACTURA " + (invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId())
            ));
        }

        // 3. 1 DEBIT line to Accounts Receivable (CARTERA) for official total (subtotal + IVA)
        lines.add(AccountingEntryLine.debit(
                sequence,
                AccountingLineType.CARTERA,
                receivablesAccount,
                invoice.getTotalAmount(),
                "CARTERA CLIENTE NIT " + invoice.getClientNit() + " - " + invoice.getClientBusinessName()
        ));

        // 4. Verify balance: sum(debits) must equal sum(credits)
        Money totalDebits = lines.stream()
                .map(AccountingEntryLine::debit)
                .reduce(Money.ZERO, Money::plus);

        Money totalCredits = lines.stream()
                .map(AccountingEntryLine::credit)
                .reduce(Money.ZERO, Money::plus);

        if (!totalDebits.equals(totalCredits)) {
            throw new UnbalancedAccountingEntryException(String.format(
                    "Accounting entry is unbalanced! Debits: %s, Credits: %s, Difference: %s",
                    totalDebits, totalCredits, totalDebits.minus(totalCredits)
            ));
        }

        return lines;
    }
}
