package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RN-07: solo una factura en preparación admite que se reemplacen sus conceptos. */
class InvoiceEditabilityTest {

    private Invoice invoice;

    private static InvoiceItem item(long base) {
        return new InvoiceItem(null, null, BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                "SEDE GR 01-30 NOV 2026", BigDecimal.ONE, Money.of(base), BigDecimal.ZERO, true, BigDecimal.valueOf(19));
    }

    @BeforeEach
    void setUp() {
        invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "FESP 1",
                LocalDate.of(2026, 11, 1), null, BillingType.F1, InvoiceStatus.DRAFT);
        invoice.addItem(item(500_000));
        invoice.consolidate();
    }

    @Test
    @DisplayName("Consolidada, sin CUFE ni lote: editable; el total sale de los conceptos nuevos")
    void consolidatedInvoiceIsEditable() {
        assertNull(invoice.editabilityBlocker());

        invoice.replaceItems(List.of(item(250_000), item(400_000)));

        assertEquals(2, invoice.getItems().size());
        assertEquals(Money.of(650_000), invoice.getSubtotalAmount());
        assertEquals(Money.of(773_500), invoice.getTotalAmount());
        assertEquals(InvoiceStatus.CONSOLIDATED, invoice.getStatus());
        assertEquals("FESP 1", invoice.getInvoiceNumber());
    }

    @Test
    @DisplayName("Con CUFE: no editable y no cambia")
    void invoiceWithCufeIsNotEditable() {
        invoice.setCufe("CUFE-1");
        assertNotNull(invoice.editabilityBlocker());
        assertThrows(IllegalStateException.class, () -> invoice.replaceItems(List.of(item(100_000))));
        assertEquals(Money.of(595_000), invoice.getTotalAmount());
    }

    @Test
    @DisplayName("En un lote de exportación a Siigo: no editable")
    void invoiceInExportBatchIsNotEditable() {
        invoice.setExportBatchId(UUID.randomUUID());
        assertTrue(invoice.editabilityBlocker().contains("lote de exportación"));
    }

    @Test
    @DisplayName("Documento oficial de Siigo: no editable")
    void officialSiigoDocumentIsNotEditable() {
        invoice.markImportedFromSiigo("F1:123");
        assertTrue(invoice.editabilityBlocker().contains("Siigo"));
    }

    @Test
    @DisplayName("Un descuento por valor en cero no anula el descuento por porcentaje del concepto")
    void zeroFixedDiscountKeepsPercentageDiscount() {
        InvoiceItem item = new InvoiceItem(null, null, BillableItemType.CORPORATE_SERVICE, UUID.randomUUID(),
                "SEDE GR 01-30 NOV 2026", BigDecimal.ONE, Money.of(500_000), BigDecimal.TEN, true, BigDecimal.valueOf(19));
        item.addFixedDiscount(Money.ZERO); // lo que deja una fila con valor_descuento_fijo = 0

        assertEquals(Money.of(50_000), item.getDiscountValue());
        assertEquals(Money.of(450_000), item.getTaxableBase());
    }

    @Test
    @DisplayName("Una lista vacía se rechaza antes de borrar los conceptos actuales")
    void emptyReplacementIsRejectedWithoutTouchingItems() {
        assertThrows(IllegalStateException.class, () -> invoice.replaceItems(List.of()));
        assertEquals(1, invoice.getItems().size());
        assertEquals(Money.of(595_000), invoice.getTotalAmount());
    }
}
