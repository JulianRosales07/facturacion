package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceBalanceSafetyTest {
    private Invoice invoice() {
        return new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "123",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), BillingType.F1, InvoiceStatus.CONSOLIDATED);
    }
    @Test void rejectsDifferentBalancesWithSameCutoff() {
        var invoice = invoice();
        var cutoff = Instant.parse("2026-10-14T15:00:00Z");
        invoice.applySiigoBalance(Money.of("69000"), cutoff, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> invoice.applySiigoBalance(Money.of("119000"), cutoff, UUID.randomUUID()));
        assertEquals(Money.of("69000"), invoice.getPendingBalance());
    }
    @Test void negativeBalanceIsCreditAndNeverAnAutomaticPayment() {
        var invoice = invoice();
        invoice.applySiigoBalance(Money.of("-1"), Instant.now(), UUID.randomUUID());
        assertEquals(Money.of("-1"), invoice.getSiigoBalance());
        assertEquals(Money.ZERO, invoice.getPendingBalance());
        assertFalse(invoice.isPayable());
        assertEquals(InvoiceStatus.CONSOLIDATED, invoice.getStatus());
    }

    @Test void localPaymentDoesNotRewriteOfficialSnapshotAndIsNotChargedTwice() {
        var invoice = invoice();
        var cutoff = Instant.parse("2026-10-13T15:00:00Z");
        invoice.applySiigoBalance(Money.of("119000"), cutoff, null);
        invoice.recordLocalSettlement(Money.of("50000"), cutoff.plusSeconds(60));
        assertEquals(Money.of("119000"), invoice.getSiigoBalance());
        assertEquals(cutoff, invoice.getSiigoBalanceCutoffAt());
        assertEquals(Money.of("69000"), invoice.getPendingBalance());
        invoice.applySiigoBalance(Money.of("119000"), cutoff.plusSeconds(120), null);
        assertEquals(Money.of("69000"), invoice.getPendingBalance());
        invoice.applySiigoBalance(Money.of("69000"), cutoff.plusSeconds(180), null);
        assertEquals(Money.ZERO, invoice.getUnreconciledLocalSettlement());
        assertEquals(Money.of("69000"), invoice.getPendingBalance());
    }

    @Test void inconsistentSnapshotCannotEraseLocalPayment() {
        var invoice = invoice();
        var cutoff = Instant.parse("2026-10-13T15:00:00Z");
        invoice.applySiigoBalance(Money.of("119000"), cutoff, null);
        invoice.recordLocalSettlement(Money.of("50000"), cutoff.plusSeconds(60));
        assertThrows(IllegalArgumentException.class,
                () -> invoice.applySiigoBalance(Money.of("90000"), cutoff.plusSeconds(120), null));
        assertEquals(Money.of("69000"), invoice.getPendingBalance());
    }
}
