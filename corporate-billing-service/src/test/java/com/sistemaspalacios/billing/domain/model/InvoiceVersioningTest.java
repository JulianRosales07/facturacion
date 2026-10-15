package com.sistemaspalacios.billing.domain.model;

import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 10: invariantes del versionado de facturas (Opción A).
 */
class InvoiceVersioningTest {

    private CorporateClient client;
    private UUID periodId;

    @BeforeEach
    void setUp() {
        periodId = UUID.randomUUID();
        client = new CorporateClient(
                UUID.randomUUID(),
                "900245045",
                "CLIENTE CORP SAS",
                "Calle 100",
                "factura@corp.com",
                "3101234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
    }

    private Invoice newInvoice(Money amount) {
        Invoice invoice = new Invoice(
                null,
                client.getId(),
                periodId,
                BillingType.F1,
                client,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 15)
        );
        invoice.addItem(new InvoiceItem(
                null,
                invoice.getId(),
                BillableItemType.CORPORATE_SERVICE,
                UUID.randomUUID(),
                "SERVICIO CORPORATIVO",
                BigDecimal.ONE,
                amount,
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        ));
        return invoice;
    }

    @Test
    @DisplayName("Una factura recién creada es la versión 1, raíz de su propia cadena y no tiene antecesora")
    void testFirstVersionDefaults() {
        Invoice invoice = newInvoice(Money.of(1_000_000));

        assertEquals(1, invoice.getVersionNumber());
        assertEquals(invoice.getId(), invoice.getOriginInvoiceId());
        assertNull(invoice.getPreviousInvoiceId());
        assertNull(invoice.getSupersededById());
        assertTrue(invoice.isFirstVersion());
        assertTrue(invoice.isCurrentVersion());
        assertFalse(invoice.isSuperseded());
    }

    @Test
    @DisplayName("Al regenerar, la v2 hereda la raíz de la cadena y la v1 queda REEMPLAZADA apuntando a la v2")
    void testSupersedeChainsBothVersions() {
        Invoice v1 = newInvoice(Money.of(1_000_000));
        v1.consolidate();

        Invoice v2 = newInvoice(Money.of(1_500_000));
        v2.markAsNextVersionOf(v1, "Llegaron novedades del mismo mes");
        v2.consolidate();

        v1.supersede(v2.getId(), "Llegaron novedades del mismo mes", "analista@sp.com");

        // Cadena
        assertEquals(2, v2.getVersionNumber());
        assertEquals(v1.getOriginInvoiceId(), v2.getOriginInvoiceId());
        assertEquals(v1.getId(), v2.getPreviousInvoiceId());
        assertEquals(v2.getId(), v1.getSupersededById());

        // La v1 se conserva como histórico y deja de ser vigente
        assertEquals(InvoiceStatus.SUPERSEDED, v1.getStatus());
        assertEquals("REEMPLAZADA", v1.getStatus().getDbValue());
        assertTrue(v1.isSuperseded());
        assertFalse(v1.isCurrentVersion());
        assertNotNull(v1.getSupersededAt());
        assertEquals("analista@sp.com", v1.getRegeneratedBy());

        // La v2 es la única vigente
        assertTrue(v2.isCurrentVersion());
        assertEquals(InvoiceStatus.CONSOLIDATED, v2.getStatus());

        // El valor de la v1 no se altera al ser reemplazada
        assertEquals(Money.of(1_190_000), v1.getTotalAmount());
        assertEquals(Money.of(1_785_000), v2.getTotalAmount());
    }

    @Test
    @DisplayName("Una versión ya reemplazada no se puede regenerar, anular ni ajustar por nota crédito")
    void testSupersededInvoiceIsImmutable() {
        Invoice v1 = newInvoice(Money.of(1_000_000));
        v1.consolidate();
        Invoice v2 = newInvoice(Money.of(1_200_000));
        v2.markAsNextVersionOf(v1, "Ajuste");
        v1.supersede(v2.getId(), "Ajuste", "analista@sp.com");

        assertFalse(v1.canBeRegenerated());
        assertThrows(IllegalStateException.class, v1::assertCanBeRegenerated);
        assertThrows(IllegalStateException.class, () -> v1.voidInvoice("Intento de anulación", "analista"));
        assertFalse(v1.canBeAdjusted());

        // La vigente sí admite ambas operaciones
        assertTrue(v2.canBeRegenerated());
        assertDoesNotThrow(v2::assertCanBeRegenerated);
    }

    @Test
    @DisplayName("Una factura pagada no se regenera: el ajuste debe ir por nota crédito")
    void testPaidInvoiceCannotBeRegenerated() {
        Invoice invoice = newInvoice(Money.of(1_000_000));
        invoice.consolidate();
        invoice.markAsPaid();

        assertFalse(invoice.canBeRegenerated());
        IllegalStateException ex = assertThrows(IllegalStateException.class, invoice::assertCanBeRegenerated);
        assertTrue(ex.getMessage().toLowerCase().contains("nota crédito"));
    }

    @Test
    @DisplayName("Una factura anulada no se regenera")
    void testVoidedInvoiceCannotBeRegenerated() {
        Invoice invoice = newInvoice(Money.of(1_000_000));
        invoice.consolidate();
        invoice.voidInvoice("Error de facturación", "analista");

        assertFalse(invoice.canBeRegenerated());
        assertThrows(IllegalStateException.class, invoice::assertCanBeRegenerated);
    }

    @Test
    @DisplayName("No se puede versionar sobre una factura de otro cliente o de otro periodo")
    void testVersioningRejectsForeignInvoice() {
        Invoice v1 = newInvoice(Money.of(1_000_000));

        Invoice otherPeriod = new Invoice(
                null, client.getId(), UUID.randomUUID(), BillingType.F1, client,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 15));

        assertThrows(IllegalArgumentException.class, () -> otherPeriod.markAsNextVersionOf(v1, "Motivo"));
        assertThrows(IllegalArgumentException.class, () -> v1.markAsNextVersionOf(v1, "Motivo"));
    }

    @Test
    @DisplayName("El motivo de la regeneración es obligatorio y queda auditado")
    void testReasonIsMandatory() {
        Invoice v1 = newInvoice(Money.of(1_000_000));
        v1.consolidate();
        Invoice v2 = newInvoice(Money.of(1_000_000));
        UUID v2Id = v2.getId();

        assertThrows(IllegalArgumentException.class, () -> v1.supersede(v2Id, "   ", "analista"));
        assertThrows(IllegalArgumentException.class, () -> v1.supersede(v2Id, null, "analista"));

        v1.supersede(v2Id, "Novedad de baja recibida el 18", "analista");
        assertEquals("Novedad de baja recibida el 18", v1.getRegenerationReason());
    }

    @Test
    @DisplayName("Solo VOIDED y SUPERSEDED dejan de ser estados vigentes")
    void testCurrentStatuses() {
        assertTrue(InvoiceStatus.DRAFT.isCurrent());
        assertTrue(InvoiceStatus.CONSOLIDATED.isCurrent());
        assertTrue(InvoiceStatus.PAID.isCurrent());
        assertFalse(InvoiceStatus.SUPERSEDED.isCurrent());
        assertFalse(InvoiceStatus.VOIDED.isCurrent());
    }

    @Test
    @DisplayName("Las cuotas de instalación se liberan al regenerar para no cobrarlas dos veces")
    void testInstallationFeeRollback() {
        InstallationFee fee = new InstallationFee(
                UUID.randomUUID(), UUID.randomUUID(), Money.of(1_200_000), 6, 0,
                Money.of(200_000), InstallationFeeStatus.PENDING);

        fee.incrementBilledInstallments(); // facturada por la v1
        assertEquals(1, fee.getBilledInstallments());
        assertEquals(InstallationFeeStatus.BILLING, fee.getStatus());

        // Al regenerar se libera y vuelve a estar pendiente
        fee.rollbackBilledInstallments(1);
        assertEquals(0, fee.getBilledInstallments());
        assertEquals(InstallationFeeStatus.PENDING, fee.getStatus());

        // La v2 la vuelve a facturar: el avance neto sigue siendo 1, no 2
        fee.incrementBilledInstallments();
        assertEquals(1, fee.getBilledInstallments());

        // No se puede liberar más de lo facturado
        assertThrows(IllegalStateException.class, () -> fee.rollbackBilledInstallments(5));
        // Liberar cero o negativo es inocuo
        assertDoesNotThrow(() -> fee.rollbackBilledInstallments(0));
        assertEquals(1, fee.getBilledInstallments());
    }

    @Test
    @DisplayName("La última cuota deja la instalación PAGADA, y liberarla la devuelve a FACTURANDO")
    void testInstallationFeeRollbackFromPaid() {
        InstallationFee fee = new InstallationFee(
                UUID.randomUUID(), UUID.randomUUID(), Money.of(400_000), 2, 1,
                Money.of(200_000), InstallationFeeStatus.BILLING);

        fee.incrementBilledInstallments();
        assertEquals(2, fee.getBilledInstallments());
        assertEquals(InstallationFeeStatus.PAID, fee.getStatus());
        assertFalse(fee.hasPendingInstallments());

        fee.rollbackBilledInstallments(1);
        assertEquals(1, fee.getBilledInstallments());
        assertEquals(InstallationFeeStatus.BILLING, fee.getStatus());
        assertTrue(fee.hasPendingInstallments());
    }

    @Test
    @DisplayName("La novedad queda atada a la versión que la facturó y se libera al dejar de ser vigente")
    void testOrderUpdateBillingTraceability() {
        OrderUpdate update = new OrderUpdate(
                UUID.randomUUID(), UUID.randomUUID(), OrderUpdateType.ALTA, periodId,
                Money.of(500_000), LocalDate.of(2026, 9, 10), true, null);

        assertFalse(update.isBilled());
        assertNull(update.getInvoiceId());

        UUID invoiceId = UUID.randomUUID();
        update.markAsBilled(invoiceId);
        assertTrue(update.isBilled());
        assertEquals(invoiceId, update.getInvoiceId());
        assertNotNull(update.getBilledAt());

        // Reasignación a la nueva versión
        UUID v2Id = UUID.randomUUID();
        update.markAsBilled(v2Id);
        assertEquals(v2Id, update.getInvoiceId());

        update.releaseFromInvoice();
        assertFalse(update.isBilled());
        assertNull(update.getInvoiceId());
        assertNull(update.getBilledAt());
    }
}
