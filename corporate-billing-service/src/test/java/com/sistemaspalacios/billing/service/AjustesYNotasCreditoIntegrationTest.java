package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus;
import com.sistemaspalacios.billing.domain.model.AdjustmentDocumentType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioDocumentoAjuste;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración para Sprint 5 (Ajustes: Anulación y Notas Crédito — HU-19, HU-20).
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class AjustesYNotasCreditoIntegrationTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioDocumentoAjuste repositorioDocumentoAjuste;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioSede repositorioSede;

    private CorporateClient client;
    private BillingPeriod period;

    @BeforeEach
    void setUp() {
        period = repositorioPeriodo.findByCode("2026-09").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(
                    UUID.randomUUID(),
                    "2026-09",
                    BillingCutType.ADVANCE,
                    LocalDate.of(2026, 9, 1),
                    LocalDate.of(2026, 9, 30),
                    true
            );
            return repositorioPeriodo.saveAndFlush(p);
        });

        client = repositorioCliente.findByNit("900999888-1").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900999888-1",
                    "EMPRESA DE PRUEBAS AJUSTES S.A.S.",
                    "CALLE 10 # 20-30",
                    "contabilidad@pruebas.com",
                    "3001234567",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    false,
                    "PASTO",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        SubscriptionSite site = new SubscriptionSite(
                UUID.randomUUID(),
                client.getId(),
                "ORD-S5-01",
                OrderOrigin.SPE,
                "NIC-S5-01",
                "INTERNET CORP SEDE PRINCIPAL",
                "PASTO",
                Money.of(new BigDecimal("2000000.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(site);
    }

    @Test
    @DisplayName("HU-19: Anulación de factura marca estado ANULADA, fecha y motivo")
    void testAnulacionFacturaRegistraMotivoYFecha() {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        assertNotNull(invoice.getId());
        assertEquals(InvoiceStatus.CONSOLIDATED, invoice.getStatus());

        String motivo = "Error en tarifa convenida con el cliente";
        billingService.voidInvoice(invoice.getId(), motivo);

        Invoice voided = billingService.getInvoiceById(invoice.getId());
        assertEquals(InvoiceStatus.VOIDED, voided.getStatus());
        assertEquals(motivo, voided.getVoidReason());
        assertNotNull(voided.getVoidedAt());
        assertTrue(voided.isVoided());
    }

    @Test
    @DisplayName("HU-19: Anular una factura habilita refacturar el mismo cliente y periodo respetando el índice único")
    void testRefacturacionTrasAnulacion() {
        // 1. Emitir primera factura
        Invoice invoice1 = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );
        assertEquals(InvoiceStatus.CONSOLIDATED, invoice1.getStatus());

        // 2. Anular primera factura
        billingService.voidInvoice(invoice1.getId(), "Ajuste por refacturación");

        // 3. Generar segunda factura para el mismo cliente y periodo (debe permitirse sin conflicto único)
        Invoice invoice2 = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        assertNotNull(invoice2.getId());
        assertNotEquals(invoice1.getId(), invoice2.getId());
        assertEquals(InvoiceStatus.CONSOLIDATED, invoice2.getStatus());

        // Verificar que ambas existen en BD: una ANULADA y una CONSOLIDADA
        Invoice inv1Reloaded = billingService.getInvoiceById(invoice1.getId());
        Invoice inv2Reloaded = billingService.getInvoiceById(invoice2.getId());
        assertEquals(InvoiceStatus.VOIDED, inv1Reloaded.getStatus());
        assertEquals(InvoiceStatus.CONSOLIDATED, inv2Reloaded.getStatus());
    }

    @Test
    @DisplayName("HU-19: Intentar anular una factura ya anulada lanza IllegalStateException")
    void testReanulacionLanzaExcepcion() {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );
        billingService.voidInvoice(invoice.getId(), "Primera anulación");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            billingService.voidInvoice(invoice.getId(), "Segunda anulación");
        });
        assertTrue(ex.getMessage().contains("ya se encuentra anulada"));
    }

    @Test
    @DisplayName("HU-20: Emisión de Nota Crédito total crea documento_ajuste ligado a factura con importes e IVA exactos")
    void testEmitirNotaCreditoTotal() {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        AdjustmentDocument nc = creditNoteService.issueCreditNote(
                invoice.getId(),
                "Devolución total por cancelación de servicio",
                "NC-2026-0001",
                null // null o vacía = nota crédito total
        );

        assertNotNull(nc.getId());
        assertEquals(invoice.getId(), nc.getInvoiceId());
        assertEquals(AdjustmentDocumentType.CREDIT_NOTE, nc.getType());
        assertEquals("NC-2026-0001", nc.getDocumentNumber());
        assertEquals("Devolución total por cancelación de servicio", nc.getReason());
        assertEquals(AdjustmentDocumentStatus.APPLIED, nc.getStatus());

        // Verificar cuadre financiero
        assertEquals(invoice.getSubtotalAmount(), nc.getSubtotalAmount());
        assertEquals(invoice.getIvaAmount(), nc.getIvaAmount());
        assertEquals(invoice.getTotalAmount(), nc.getNetAmount());
        assertFalse(nc.getItems().isEmpty());

        // Consultar por factura
        List<AdjustmentDocument> list = creditNoteService.getCreditNotesByInvoice(invoice.getId());
        assertEquals(1, list.size());
        assertEquals(nc.getId(), list.get(0).getId());
    }

    @Test
    @DisplayName("HU-20: Emisión de Nota Crédito parcial con ítems específicos y cálculo proporcional")
    void testEmitirNotaCreditoParcial() {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        InvoiceItem originalItem = invoice.getItems().get(0);

        CreditNoteService.AdjustmentItemCommand partialItem = new CreditNoteService.AdjustmentItemCommand(
                originalItem.getId(),
                "Ajuste descuento 500,000 en sede",
                BigDecimal.ONE,
                Money.of(new BigDecimal("500000.00")),
                Money.ZERO,
                BigDecimal.valueOf(19)
        );

        AdjustmentDocument nc = creditNoteService.issueCreditNote(
                invoice.getId(),
                "Descuento comercial extraordinario",
                null, // Generación automática de número
                List.of(partialItem)
        );

        assertNotNull(nc.getId());
        assertTrue(nc.getDocumentNumber().startsWith("NC-"));
        assertEquals(Money.of(new BigDecimal("500000.00")), nc.getSubtotalAmount());
        assertEquals(Money.of(new BigDecimal("95000.00")), nc.getIvaAmount()); // 19% de 500.000
        assertEquals(Money.of(new BigDecimal("595000.00")), nc.getNetAmount());
    }

    @Test
    @DisplayName("HU-20: Emitir Nota Crédito sobre factura anulada lanza IllegalStateException")
    void testNotaCreditoSobreFacturaAnuladaLanzaExcepcion() {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );
        billingService.voidInvoice(invoice.getId(), "Anulación administrativa previa");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            creditNoteService.issueCreditNote(
                    invoice.getId(),
                    "Intento de nota crédito",
                    null,
                    null
            );
        });
        assertTrue(ex.getMessage().contains("anulada"));
    }

    @Test
    @DisplayName("HU-20: Emitir Nota Crédito con valor superior al de la factura lanza IllegalArgumentException")
    void testNotaCreditoSuperaValorFacturaLanzaExcepcion() {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        CreditNoteService.AdjustmentItemCommand excessiveItem = new CreditNoteService.AdjustmentItemCommand(
                null,
                "Ajuste excesivo",
                BigDecimal.ONE,
                Money.of(new BigDecimal("999999999.00")),
                Money.ZERO,
                BigDecimal.valueOf(19)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            creditNoteService.issueCreditNote(
                    invoice.getId(),
                    "Descuento desmedido",
                    null,
                    List.of(excessiveItem)
            );
        });
        assertTrue(ex.getMessage().contains("no puede superar el valor total"));
    }
}
