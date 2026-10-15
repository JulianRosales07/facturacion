package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
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
 * Prueba de resiliencia del lote: El fallo de un registro individual o factura previa activa
 * no detiene la generación del resto de clientes del corte (RNF Resiliencia - Sprint 7).
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class BatchResilienceIntegrationTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private RepositorioFactura repositorioFactura;

    private BillingPeriod period;
    private CorporateClient clientA;
    private CorporateClient clientB;

    @BeforeEach
    void setUp() {
        period = repositorioPeriodo.findByCode("2026-10").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(
                    UUID.randomUUID(),
                    "2026-10",
                    BillingCutType.ADVANCE,
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 31),
                    true
            );
            return repositorioPeriodo.saveAndFlush(p);
        });

        // Cliente A: con sede activa
        clientA = repositorioCliente.findByNit("900999001-1").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900999001-1",
                    "CLIENTE A CORP",
                    "CARRERA 1 # 1-01",
                    "facturacion@clientea.com",
                    "3101111111",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    false,
                    "BOGOTA",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        SubscriptionSite siteA = new SubscriptionSite(
                UUID.randomUUID(),
                clientA.getId(),
                "ORD-SIT-A1",
                OrderOrigin.SPE,
                "Sede A1",
                "Carrera 1 # 1-01",
                "Bogota",
                Money.of(new BigDecimal("1500000.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(siteA);

        // Cliente B: con sede activa
        clientB = repositorioCliente.findByNit("900999002-2").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900999002-2",
                    "CLIENTE B CORP",
                    "CALLE 2 # 2-02",
                    "facturacion@clienteb.com",
                    "3102222222",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    false,
                    "MEDELLIN",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        SubscriptionSite siteB = new SubscriptionSite(
                UUID.randomUUID(),
                clientB.getId(),
                "ORD-SIT-B1",
                OrderOrigin.SPE,
                "Sede B1",
                "Calle 2 # 2-02",
                "Medellin",
                Money.of(new BigDecimal("2200000.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(siteB);

        // Pre-crear factura activa para Cliente A en este periodo para inducir colisión/fallo
        Invoice preExistingInvoice = new Invoice(
                UUID.randomUUID(),
                clientA.getId(),
                period.getId(),
                BillingType.F1,
                clientA,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31)
        );
        InvoiceItem item = new InvoiceItem(
                null,
                preExistingInvoice.getId(),
                BillableItemType.CORPORATE_SERVICE,
                siteA.getId(),
                "SERVICIO PREVIO",
                BigDecimal.ONE,
                Money.of(1500000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        );
        preExistingInvoice.addItem(item);
        preExistingInvoice.consolidate();
        repositorioFactura.saveAndFlush(preExistingInvoice);
    }

    @Test
    @DisplayName("Resiliencia: Factura activa preexistente de Cliente A es omitida sin abortar el lote para Cliente B")
    void testBatchGenerationIsResilientToIndividualClientErrors() {
        // Ejecución en lote para todo el corte (nit == null)
        List<Invoice> generated = billingService.generateConsolidated("2026-10", BillingType.F1, BillingCutType.ADVANCE, null);

        assertNotNull(generated);
        assertFalse(generated.isEmpty());

        // Verificar que Cliente B fue facturado exitosamente en el lote
        boolean clientBGenerated = generated.stream().anyMatch(inv -> inv.getClientId().equals(clientB.getId()));
        assertTrue(clientBGenerated, "El lote debió generar la factura de Cliente B a pesar del conflicto con Cliente A");

        // Verificar que Cliente A no se duplicó
        long countA = repositorioFactura.findByPeriodId(period.getId()).stream()
                .filter(inv -> inv.getClientId().equals(clientA.getId()))
                .count();
        assertEquals(1, countA, "Cliente A debe mantener únicamente su factura activa previa");
    }
}
