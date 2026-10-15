package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.vo.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** El PDF que genera el backend para cada factura usa solo los datos de esa factura. */
class InvoiceGraphicRendererDataTest {

    private final SiigoInvoiceGraphicRenderer renderer = new SiigoInvoiceGraphicRenderer();

    @Test
    void portfolioBalanceCannotBeRenderedAsAnOfficialInvoice() {
        var invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "123",
                LocalDate.of(2026, 9, 8), null, BillingType.F1, InvoiceStatus.CONSOLIDATED);
        invoice.markImportedFromSiigo("F1:123");
        invoice.addItem(InvoiceItem.fromOfficialAmounts(invoice.getId(), Invoice.CARTERA_PLACEHOLDER_CODE,
                Invoice.CARTERA_PLACEHOLDER_DESCRIPTION, BigDecimal.ONE, Money.ZERO, Money.ZERO));
        assertThrows(IllegalStateException.class, () -> renderer.renderHtml(invoice));
    }

    private Invoice invoice(String number, BillingType type, long unitPrice) {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), number,
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 23), type, InvoiceStatus.CONSOLIDATED);
        invoice.setClientNit("1085285659");
        invoice.setClientBusinessName("CLIENTE DE PRUEBA");
        invoice.addItem(new InvoiceItem(null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, null,
                "INTERNET DEDICADO 100MB SEDE CENTRO", BigDecimal.ONE, Money.of(unitPrice), BigDecimal.ZERO, true, BigDecimal.valueOf(19)));
        invoice.recalculateTotals();
        return invoice;
    }

    @Test
    @DisplayName("Cada factura muestra su número, su servicio y su valor; nunca datos de otro cliente o un CUFE ajeno")
    void rendersOnlyThisInvoiceData() {
        String html = renderer.renderHtml(invoice("51980", BillingType.F1, 85_000));

        assertTrue(html.contains("FESP 51980"));
        assertTrue(html.contains("INTERNET DEDICADO 100MB SEDE CENTRO"));
        assertTrue(html.contains("1085285659"));
        assertTrue(html.contains("101.150,00") || html.contains("101,150.00"), "total oficial de la factura (85.000 + IVA)");
        assertTrue(html.contains(SiigoInvoiceGraphicRenderer.CUFE_PENDING), "sin CUFE no se inventa uno");
        assertTrue(html.contains(SiigoInvoiceGraphicRenderer.NOT_INFORMED), "dirección/correo desconocidos no se rellenan");
        assertTrue(html.contains("QR no disponible"), "sin QR real no se dibuja un QR simulado");

        for (String foreign : new String[]{"720377e072ca996f78d1f873a16899a51af08fc5ca705c4ebdfa9841c2320214396cd348fac2e601082dce87023b26e3",
                "3135963672", "LUZ MARY", "LA CRUZ", "10:06:35", "0100040000021", "FESP 51993"}) {
            assertFalse(html.contains(foreign), "dato ajeno a la factura: " + foreign);
        }
    }

    @Test
    @DisplayName("Dos facturas del mismo NIT producen documentos distintos, cada uno con su servicio")
    void eachInvoiceHasItsOwnDocument() {
        String a = renderer.renderHtml(invoice("51980", BillingType.F1, 85_000));
        String b = renderer.renderHtml(invoice("287", BillingType.F20, 200_000));
        assertTrue(a.contains("FESP 51980") && !a.contains("FEMG 287"));
        assertTrue(b.contains("FEMG 287") && !b.contains("FESP 51980"));
    }
}
