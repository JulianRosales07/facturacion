package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService.ClientInvoiceGroup;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService.GroupedInvoice;
import com.sistemaspalacios.billing.service.IncomingInvoiceQueryService.InvoiceRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncomingInvoiceGroupingTest {

    private static InvoiceRow row(String nit, BillingType type, String number, String total) {
        return new InvoiceRow(UUID.randomUUID(), number, nit, "EMPRESA " + nit, type,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), new BigDecimal(total), false, null);
    }

    private static BigDecimal sumOfItems(ClientInvoiceGroup g) {
        return g.invoices().stream().map(GroupedInvoice::serviceValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void nitWithSingleInvoiceTotalEqualsThatInvoice() {
        var groups = IncomingInvoiceQueryService.groupByNit(List.of(row("900111111", BillingType.F1, "100", "200000")));

        assertEquals(1, groups.size());
        var g = groups.getFirst();
        assertEquals("900111111", g.nit());
        assertEquals(1, g.invoices().size());
        assertEquals("F1:100", g.invoices().getFirst().documentKey());
        assertEquals(0, new BigDecimal("200000").compareTo(g.totalServiceValue()));
        assertEquals(g.invoices().getFirst().serviceValue(), g.totalServiceValue());
    }

    @Test
    void nitWithSeveralInvoicesIsOneObjectWithTheSum() {
        var groups = IncomingInvoiceQueryService.groupByNit(List.of(
                row("900222222", BillingType.F1, "101", "300000"),
                row("900222222", BillingType.F1, "102", "150000"),
                row("900222222", BillingType.F20, "50", "250000")));

        assertEquals(1, groups.size());
        var g = groups.getFirst();
        assertEquals(3, g.invoices().size());
        assertEquals(3, g.invoiceCount());
        assertEquals(List.of("F1:101", "F1:102", "F20:50"), g.invoices().stream().map(GroupedInvoice::documentKey).toList());
        assertEquals(0, new BigDecimal("700000").compareTo(g.totalServiceValue()));
        assertEquals(sumOfItems(g), g.totalServiceValue());
    }

    @Test
    void duplicatedInvoiceIsAddedAndSummedOnlyOnce() {
        var groups = IncomingInvoiceQueryService.groupByNit(List.of(
                row("900333333", BillingType.F1, "51993", "100000"),
                row("900333333", BillingType.F1, "51993", "100000"),   // duplicado exacto
                row("900333333", BillingType.F1, "0051993", "100000"), // mismo documento con ceros a la izquierda
                row("900333333", BillingType.F20, "51993", "40000"))); // mismo n\u00famero, otro tipo: es otro documento

        var g = groups.getFirst();
        assertEquals(2, g.invoices().size());
        assertEquals(List.of("F1:51993", "F20:51993"), g.invoices().stream().map(GroupedInvoice::documentKey).toList());
        assertEquals(0, new BigDecimal("140000").compareTo(g.totalServiceValue()));
    }

    @Test
    void differentNitsAreNeverMergedAndNoNitIsRepeated() {
        List<InvoiceRow> rows = new ArrayList<>();
        // 50 facturas repartidas en 20 NIT (algunos NIT con 1, otros con varias).
        for (int i = 0; i < 50; i++) {
            rows.add(row("9000000" + String.format("%02d", i % 20), i % 3 == 0 ? BillingType.F20 : BillingType.F1,
                    String.valueOf(1000 + i), "1000.10"));
        }
        var groups = IncomingInvoiceQueryService.groupByNit(rows);

        assertEquals(20, groups.size());
        assertEquals(groups.size(), new HashSet<>(groups.stream().map(ClientInvoiceGroup::nit).toList()).size(),
                "Ning\u00fan NIT debe aparecer dos veces");
        assertEquals(50, groups.stream().mapToInt(g -> g.invoices().size()).sum(), "No se pierde ninguna factura");
        for (var g : groups) {
            assertEquals(sumOfItems(g), g.totalServiceValue(), "valor_total_servicio = \u03a3 valor_servicio para " + g.nit());
            assertTrue(g.invoices().stream().allMatch(inv -> rows.stream()
                    .anyMatch(r -> r.id().equals(inv.id()) && r.clientNit().equals(g.nit()))), "Facturas de otro NIT en " + g.nit());
        }
        BigDecimal grandTotal = groups.stream().map(ClientInvoiceGroup::totalServiceValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("50005.00"), grandTotal); // 50 \u00d7 1000.10, sin error de coma flotante
    }

    @Test
    void decimalAmountsAreSummedWithoutPrecisionLoss() {
        var g = IncomingInvoiceQueryService.groupByNit(List.of(
                row("900444444", BillingType.F1, "1", "0.10"),
                row("900444444", BillingType.F1, "2", "0.20"),
                row("900444444", BillingType.F1, "3", "1122566.6599999999"))).getFirst();
        // 0.10 + 0.20 + 1122566.66 (HALF_UP) = 1122566.96 exacto
        assertEquals(new BigDecimal("1122566.96"), g.totalServiceValue());
        assertEquals(sumOfItems(g), g.totalServiceValue());
    }

    @Test
    void renderGroupHtmlShowsAccountStatementWithoutInventingInvoiceTaxesOrCufe() {
        var g = IncomingInvoiceQueryService.groupByNit(List.of(
                row("800092198", BillingType.F1, "51993", "2722500"),
                row("800092198", BillingType.F1, "51994", "907500"),
                row("800092198", BillingType.F20, "287", "660000"))).getFirst();

        SiigoInvoiceGraphicRenderer renderer = new SiigoInvoiceGraphicRenderer();
        String html = renderer.renderGroupHtml(g);

        assertNotNull(html);
        assertTrue(html.contains("Estado de cuenta"));
        assertTrue(html.contains("800092198"));
        assertTrue(html.contains("F1 51993"));
        assertTrue(html.contains("F20 287"));
        assertTrue(html.contains("4,290,000.00")); // 2722500 + 907500 + 660000
        assertFalse(html.contains("CUFE:"));
        assertFalse(html.contains("Software:SIIGO"));
    }
}
