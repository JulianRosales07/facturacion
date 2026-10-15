package com.sistemaspalacios.billing.domain.service;

import com.sistemaspalacios.billing.domain.model.AccountingLineType;
import com.sistemaspalacios.billing.domain.model.AccountingNature;
import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.vo.AccountingEntryLine;
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

class AccountingEntryGeneratorTest {

    private AccountingEntryGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new AccountingEntryGenerator();
    }

    @Test
    @DisplayName("HU-16: Entry is strictly balanced (debits == credits) and has NO withholdings (Rule 9)")
    void testAccountingEntryGenerationAndBalance() {
        CorporateClient client = new CorporateClient(
                UUID.randomUUID(),
                "900245045",
                "CLIENTE CONSOLIDADO SAS",
                "Calle 10",
                "facturacion@corp.com",
                "3001234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                true,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );

        Invoice invoice = new Invoice(
                UUID.randomUUID(),
                client.getId(),
                UUID.randomUUID(),
                BillingType.F1,
                client,
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 30)
        );

        // Add 3 sites (revenue items)
        invoice.addItem(new InvoiceItem(
                null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                "SEDE NORTE", BigDecimal.ONE, Money.of(2_000_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)
        ));
        invoice.addItem(new InvoiceItem(
                null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                "SEDE SUR", BigDecimal.ONE, Money.of(1_500_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)
        ));
        invoice.addItem(new InvoiceItem(
                null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                "SEDE OCCIDENTE", BigDecimal.ONE, Money.of(1_500_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)
        ));

        // Subtotal = 5,000,000, IVA = 950,000, Total = 5,950,000
        assertEquals(Money.of(5_000_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(950_000), invoice.getIvaAmount());
        assertEquals(Money.of(5_950_000), invoice.getTotalAmount());

        List<AccountingEntryLine> lines = generator.generateEntry(invoice, null);

        // Lines: 3 Revenue (Credit) + 1 IVA (Credit) + 1 Cartera (Debit) = 5 lines total
        assertEquals(5, lines.size());

        // Invariant: NO withholding lines in accounting entry!
        boolean containsWithholding = lines.stream().anyMatch(l ->
                l.description().toLowerCase().contains("rete") ||
                l.accountCode().startsWith("2365") ||
                l.accountCode().startsWith("2368")
        );
        assertFalse(containsWithholding, "The accounting entry must NEVER include withholding lines (Rule 9)");

        // Check sum of debits == sum of credits
        Money totalDebits = lines.stream().map(AccountingEntryLine::debit).reduce(Money.ZERO, Money::plus);
        Money totalCredits = lines.stream().map(AccountingEntryLine::credit).reduce(Money.ZERO, Money::plus);

        assertEquals(Money.of(5_950_000), totalDebits);
        assertEquals(Money.of(5_950_000), totalCredits);
        assertEquals(totalDebits, totalCredits);

        // Check specific accounts
        AccountingEntryLine carteraLine = lines.stream().filter(l -> l.lineType() == AccountingLineType.CARTERA).findFirst().orElseThrow();
        assertEquals(AccountingNature.D, carteraLine.nature());
        assertEquals(AccountingEntryGenerator.DEFAULT_RECEIVABLES_ACCOUNT, carteraLine.accountCode());
        assertEquals(Money.of(5_950_000), carteraLine.debit());

        AccountingEntryLine ivaLine = lines.stream().filter(l -> l.lineType() == AccountingLineType.IVA).findFirst().orElseThrow();
        assertEquals(AccountingNature.C, ivaLine.nature());
        assertEquals(AccountingEntryGenerator.DEFAULT_IVA_ACCOUNT, ivaLine.accountCode());
        assertEquals(Money.of(950_000), ivaLine.credit());
    }

    @Test
    @DisplayName("HU-16: Validation with the real reference case from Prompt Section 9 (31 sites)")
    void testRealReferenceCaseSection9() {
        CorporateClient client = new CorporateClient(
                UUID.randomUUID(), "900245045", "CORPORATIVO REFERENCIA", "Dir", "email", "tel",
                (short) 1, (short) 1, BillingType.F1, true, "Pasto", TaxProfile.withoutWithholding("Pasto")
        );

        Invoice invoice = new Invoice(
                UUID.randomUUID(), client.getId(), UUID.randomUUID(), BillingType.F1, client,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 30)
        );

        // 31 sites with integer amounts summing exactly to 11,060,000 and IVA to 2,101,400:
        // 26 sites of 350,000 = 9,100,000 (IVA each: 66,500 * 26 = 1,729,000)
        // 4 sites of 400,000 = 1,600,000 (IVA each: 76,000 * 4 = 304,000)
        // 1 site of 360,000 = 360,000 (IVA: 68,400)
        // Total subtotal = 11,060,000; Total IVA = 2,101,400; Total Cartera = 13,161,400
        for (int i = 0; i < 26; i++) {
            invoice.addItem(new InvoiceItem(
                    null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                    "SEDE " + (i + 1), BigDecimal.ONE, Money.of(350_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)
            ));
        }
        for (int i = 26; i < 30; i++) {
            invoice.addItem(new InvoiceItem(
                    null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                    "SEDE " + (i + 1), BigDecimal.ONE, Money.of(400_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)
            ));
        }
        invoice.addItem(new InvoiceItem(
                null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                "SEDE 31", BigDecimal.ONE, Money.of(360_000), BigDecimal.ZERO, true, BigDecimal.valueOf(19)
        ));

        assertEquals(Money.of(11_060_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(2_101_400), invoice.getIvaAmount()); // 11,060,000 * 19% = 2,101,400
        assertEquals(Money.of(13_161_400), invoice.getTotalAmount()); // 13,161,400

        List<AccountingEntryLine> entry = generator.generateEntry(invoice, null);

        Money debits = entry.stream().map(AccountingEntryLine::debit).reduce(Money.ZERO, Money::plus);
        Money credits = entry.stream().map(AccountingEntryLine::credit).reduce(Money.ZERO, Money::plus);

        assertEquals(Money.of(13_161_400), debits);
        assertEquals(Money.of(13_161_400), credits);
        assertEquals(33, entry.size()); // 31 revenue + 1 IVA + 1 Cartera
    }
}
