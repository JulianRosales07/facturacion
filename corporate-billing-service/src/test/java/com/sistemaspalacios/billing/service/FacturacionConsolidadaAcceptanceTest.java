package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioCuotaInstalacion;
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
 * Prueba de aceptación end-to-end del Sprint 4:
 * Cliente con 3 sedes activas, una con alta a mitad de periodo y una con cuota pendiente
 * -> Factura con cabecera, ítems por sede, prorrateo correcto, IVA, totales que cuadran, y unicidad respetada.
 * Caso de "ya existe factura activa" devuelve error claro.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class FacturacionConsolidadaAcceptanceTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private RepositorioCuotaInstalacion repositorioCuotaInstalacion;

    @Autowired
    private RepositorioFactura repositorioFactura;

    private BillingPeriod testPeriod;
    private CorporateClient testClient;
    private SubscriptionSite site1FullMonth;
    private SubscriptionSite site2MidMonth;
    private SubscriptionSite site3WithFee;
    private InstallationFee pendingFee;

    @BeforeEach
    void setUp() {
        // 1. Periodo de facturación: 2026-08 (1 al 31 de agosto)
        testPeriod = repositorioPeriodo.findByCode("2026-08").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(
                    UUID.randomUUID(),
                    "2026-08",
                    BillingCutType.ADVANCE,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 31),
                    true
            );
            return repositorioPeriodo.saveAndFlush(p);
        });

        // 2. Cliente corporativo de prueba (NIT 1,088,970,617 - 6 del PDF de referencia)
        testClient = repositorioCliente.findByNit("1088970617-6").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "1088970617-6",
                    "CRISTIAN FERNANDO BRAVO MUÑOZ",
                    "LA CRUZ - NUEVO PUNTO",
                    "crisfe8609@gmail.com",
                    "3135963672",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    false,
                    "LA CRUZ",
                    null
            );
            c.setCostCenter(10);
            return repositorioCliente.saveAndFlush(c);
        });

        // 3. Sede 1: Mes completo ($2,722,500)
        site1FullMonth = new SubscriptionSite(
                UUID.randomUUID(),
                testClient.getId(),
                "ORD-ACCEPT-01",
                OrderOrigin.SPE,
                "NIC-ACCEPT-01",
                "LA CRUZ.INT.FIB.DEDI.1.65 GB",
                "LA CRUZ",
                Money.of(new BigDecimal("2722500.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(site1FullMonth);

        // 4. Sede 2: Alta a mitad de periodo (16 de agosto, valor mensual $907,500)
        // Mes comercial: del 16 al 31 son 15 días comerciales (el 31 no cuenta) -> 907,500 * 15 / 30 = 453,750
        site2MidMonth = new SubscriptionSite(
                UUID.randomUUID(),
                testClient.getId(),
                "ORD-ACCEPT-02",
                OrderOrigin.SPE,
                "NIC-ACCEPT-02",
                "B/MADRIGAL INT.FIB.DEDI 550 GB",
                "LA CRUZ",
                Money.of(new BigDecimal("907500.00")),
                LocalDate.of(2026, 8, 16),
                true
        );
        repositorioSede.saveAndFlush(site2MidMonth);

        // 5. Sede 3: Mes completo ($660,000) con cuota de instalación pendiente ($247,500)
        site3WithFee = new SubscriptionSite(
                UUID.randomUUID(),
                testClient.getId(),
                "ORD-ACCEPT-03",
                OrderOrigin.SPE,
                "NIC-ACCEPT-03",
                "CERRO CRUZ INT.FIB.DEDI.400 GB",
                "LA CRUZ",
                Money.of(new BigDecimal("660000.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(site3WithFee);

        pendingFee = new InstallationFee(
                UUID.randomUUID(),
                site3WithFee.getId(),
                Money.of(new BigDecimal("742500.00")),
                3,
                0,
                Money.of(new BigDecimal("247500.00")),
                InstallationFeeStatus.PENDING
        );
        repositorioCuotaInstalacion.saveAndFlush(pendingFee);
    }

    @Test
    @DisplayName("Sprint 4 Acceptance: Generación consolidada con 3 sedes (1 mes completo, 1 alta mitad periodo prorrateada, 1 con cuota instalación)")
    void testGenerarFacturaConsolidadaEndToEnd() {
        // Ejecución del flujo principal
        Invoice factura = billingService.generateConsolidatedInvoice(
                testPeriod.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                testClient.getNit()
        );

        // Aserciones de agregación y cabecera
        assertNotNull(factura);
        assertNotNull(factura.getId());
        assertEquals(testClient.getId(), factura.getClientId());
        assertEquals(testPeriod.getId(), factura.getPeriodId());
        assertEquals(InvoiceStatus.CONSOLIDATED, factura.getStatus());
        assertEquals("1088970617-6", factura.getClientNit());
        assertEquals("CRISTIAN FERNANDO BRAVO MUÑOZ", factura.getClientBusinessName());
        assertNotNull(factura.getInvoiceNumber());
        assertTrue(factura.getInvoiceNumber().startsWith("FESP"));

        // Aserciones de ítems facturados (3 sedes + 1 cuota = 4 ítems)
        List<InvoiceItem> items = factura.getItems();
        assertEquals(4, items.size(), "Debe contener 4 ítems: 3 servicios de sedes + 1 cuota de instalación");

        // Ítem 1 (Sede 1 mes completo): $2,722,500
        InvoiceItem itemSede1 = items.stream()
                .filter(i -> site1FullMonth.getId().equals(i.getSiteId()) && !i.getDescription().contains("CUOTA"))
                .findFirst().orElseThrow();
        assertEquals(Money.of(new BigDecimal("2722500.00")), itemSede1.getUnitPrice());

        // Ítem 2 (Sede 2 prorrateada 15 días comerciales): 907,500 * 15 / 30 = 453,750
        InvoiceItem itemSede2 = items.stream()
                .filter(i -> site2MidMonth.getId().equals(i.getSiteId()))
                .findFirst().orElseThrow();
        assertEquals(Money.of(new BigDecimal("453750.00")), itemSede2.getUnitPrice());

        // Ítem 3 (Sede 3 mes completo): $660,000
        InvoiceItem itemSede3 = items.stream()
                .filter(i -> site3WithFee.getId().equals(i.getSiteId()) && !i.getDescription().contains("CUOTA"))
                .findFirst().orElseThrow();
        assertEquals(Money.of(new BigDecimal("660000.00")), itemSede3.getUnitPrice());

        // Ítem 4 (Cuota instalación Sede 3): $247,500
        InvoiceItem itemCuota = items.stream()
                .filter(i -> i.getDescription().contains("CUOTA INSTALACION"))
                .findFirst().orElseThrow();
        assertEquals(Money.of(new BigDecimal("247500.00")), itemCuota.getUnitPrice());
        assertTrue(itemCuota.getDescription().contains("(1/3)"), "Debe indicar cuota 1 de 3");

        // Verificación de totales calculados (sin retenciones en el valor oficial):
        // Subtotal = 2,722,500 + 453,750 + 660,000 + 247,500 = 4,083,750
        // IVA 19% = 4,083,750 * 0.19 = 775,912.50
        // Total Oficial = 4,083,750 + 775,912.50 = 4,859,662.50
        Money expectedSubtotal = Money.of(new BigDecimal("4083750.00"));
        Money expectedIva = Money.of(new BigDecimal("775912.50"));
        Money expectedTotal = Money.of(new BigDecimal("4859662.50"));

        assertEquals(expectedSubtotal, factura.getSubtotalAmount());
        assertEquals(expectedIva, factura.getIvaAmount());
        assertEquals(expectedTotal, factura.getTotalAmount(), "Total oficial debe ser exactamente subtotal + IVA");
        assertEquals(expectedTotal, factura.getNetPayableAmount(), "Al no tener retenciones el cliente, neto a pagar = total");

        // Verificación de actualización de la cuota en BD
        InstallationFee cuotaActualizada = repositorioCuotaInstalacion.findById(pendingFee.getId()).orElseThrow();
        assertEquals(1, cuotaActualizada.getBilledInstallments());
        assertEquals(InstallationFeeStatus.BILLING, cuotaActualizada.getStatus());

        // Verificación de representación gráfica oficial en HTML
        String html = billingService.renderInvoiceHtml(factura.getId());
        assertNotNull(html);
        assertTrue(html.contains("SP SISTEMAS PALACIOS LTDA"));
        assertTrue(html.contains("1088970617-6"));
        assertTrue(html.contains("FACTURA ELECTRÓNICA DE VENTA"));
        assertTrue(html.contains("Generado por el sistema de facturación"));
        assertFalse(html.contains("LUZ MARY CORDOB"), "sin datos de otra persona en la factura");
        assertTrue(html.contains("Software:SIIGO"));
    }

    @Test
    @DisplayName("Sprint 4 Invariante: Reintentar generar con factura activa lanza FacturaActivaYaExisteException")
    void testRechazaGenerarFacturaSiYaExisteActiva() {
        // 1. Generar primera factura activa
        Invoice factura1 = billingService.generateConsolidatedInvoice(
                testPeriod.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                testClient.getNit()
        );
        assertNotNull(factura1);

        // 2. Reintento debe lanzar FacturaActivaYaExisteException
        FacturaActivaYaExisteException ex = assertThrows(FacturaActivaYaExisteException.class, () -> {
            billingService.generateConsolidatedInvoice(
                    testPeriod.getCode(),
                    BillingType.F1,
                    BillingCutType.ADVANCE,
                    testClient.getNit()
            );
        });

        assertEquals(testClient.getNit(), ex.getClientNit());
        assertEquals(testPeriod.getCode(), ex.getPeriodCode());
        assertEquals(factura1.getId(), ex.getExistingInvoiceId());
    }

    @Test
    @DisplayName("Sprint 4 Refacturación: Anular la factura activa permite generar una nueva para el mismo cliente y periodo")
    void testAnularHabilitaNuevaFacturaActiva() {
        // 1. Generar primera factura
        Invoice factura1 = billingService.generateConsolidatedInvoice(
                testPeriod.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                testClient.getNit()
        );

        // 2. Anular factura 1
        billingService.voidInvoice(factura1.getId(), "Error en datos pactados");
        Invoice anulada = billingService.getInvoiceById(factura1.getId());
        assertEquals(InvoiceStatus.VOIDED, anulada.getStatus());
        assertEquals("Error en datos pactados", anulada.getVoidReason());

        // 3. Generar segunda factura para el mismo cliente y periodo: ¡Debe tener éxito!
        Invoice factura2 = billingService.generateConsolidatedInvoice(
                testPeriod.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                testClient.getNit()
        );

        assertNotNull(factura2);
        assertNotEquals(factura1.getId(), factura2.getId());
        assertEquals(InvoiceStatus.CONSOLIDATED, factura2.getStatus());
    }
}
