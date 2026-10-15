package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioCuotaInstalacion;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de rendimiento (RNF Rendimiento - Sprint 7):
 * Validación con cliente corporativo de alta densidad (45 sedes activas),
 * combinando tarifas regulares, novedades a mitad de periodo y cuotas de instalación.
 * Objetivo de SLA: Generación, cálculo, persistencia y consolidación en menos de 2000 ms.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class BillingPerformanceIntegrationTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private RepositorioNovedad repositorioNovedad;

    @Autowired
    private RepositorioCuotaInstalacion repositorioCuotaInstalacion;

    @Autowired
    private RepositorioFactura repositorioFactura;

    private BillingPeriod period;
    private CorporateClient enterpriseClient;
    private static final int TOTAL_SITES = 45;

    @BeforeEach
    void setUp() {
        period = repositorioPeriodo.findByCode("2026-11").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(
                    UUID.randomUUID(),
                    "2026-11",
                    BillingCutType.ADVANCE,
                    LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 30),
                    true
            );
            return repositorioPeriodo.saveAndFlush(p);
        });

        enterpriseClient = repositorioCliente.findByNit("900888777-9").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900888777-9",
                    "REPSOL PETROLEOS DE COLOMBIA S.A.",
                    "AUTOPISTA NORTE # 120-45",
                    "facturacion.corporativa@repsol.com",
                    "3189998877",
                    (short) 2,
                    (short) 3,
                    BillingType.F1,
                    false,
                    "BOGOTA",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        // Crear 45 sedes para simular cliente masivo
        for (int i = 1; i <= TOTAL_SITES; i++) {
            SubscriptionSite site = new SubscriptionSite(
                    UUID.randomUUID(),
                    enterpriseClient.getId(),
                    "ORD-REP-" + String.format("%03d", i),
                    OrderOrigin.SPE,
                    "Estacion / Sede " + i,
                    "Zona Operativa " + (i % 5 + 1),
                    "Municipio " + (i % 10 + 1),
                    Money.of(new BigDecimal("1200000.00")),
                    LocalDate.of(2026, 1, 1),
                    true
            );
            site = repositorioSede.saveAndFlush(site);

            // Sede 10: Novedad de alta a mitad de periodo (día 15) -> Prorrateo
            if (i == 10) {
                OrderUpdate update = new OrderUpdate(
                        UUID.randomUUID(),
                        site.getId(),
                        OrderUpdateType.ALTA,
                        period.getId(),
                        Money.ZERO,
                        LocalDate.of(2026, 11, 15),
                        true,
                        "Activacion nueva sede en mitad de ciclo"
                );
                repositorioNovedad.saveAndFlush(update);
            }

            // Sede 20: Cuota de instalación pendiente
            if (i == 20) {
                InstallationFee fee = new InstallationFee(
                        UUID.randomUUID(),
                        site.getId(),
                        Money.of(new BigDecimal("3000000.00")),
                        3,
                        1,
                        Money.of(new BigDecimal("1000000.00")),
                        com.sistemaspalacios.billing.domain.model.InstallationFeeStatus.BILLING
                );
                repositorioCuotaInstalacion.saveAndFlush(fee);
            }
        }
    }

    @Test
    @DisplayName("Rendimiento: Consolidación de cliente con 45 sedes se ejecuta en < 2000 ms con cálculo financiero exacto")
    void testPerformanceConsolidatedBilling45Sites() {
        Instant start = Instant.now();

        Invoice invoice = billingService.generateConsolidatedInvoice(
                "2026-11",
                BillingType.F1,
                BillingCutType.ADVANCE,
                enterpriseClient.getNit()
        );

        Instant finish = Instant.now();
        long elapsedMs = Duration.between(start, finish).toMillis();

        System.out.println(">>> Tiempo de generación consolidada para 45 sedes: " + elapsedMs + " ms");

        // 1. Verificación de SLA de tiempo (< 2000 ms)
        assertTrue(elapsedMs < 2000, "La generación para 45 sedes tomó " + elapsedMs + " ms, superando el SLA de 2000 ms");

        // 2. Verificaciones funcionales de exactitud
        assertNotNull(invoice);
        assertNotNull(invoice.getId());
        assertEquals("REPSOL PETROLEOS DE COLOMBIA S.A.", invoice.getClientBusinessName());

        // 45 sedes de servicio + 1 ítem de cuota de instalación = 46 ítems
        assertEquals(46, invoice.getItems().size(), "Debe contener 45 servicios de sedes + 1 cuota de instalación");

        // Totales financieros en doble partida
        assertTrue(invoice.getTotalAmount().getAmount().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(invoice.getIvaAmount().getAmount().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(invoice.getSubtotalAmount().plus(invoice.getIvaAmount()), invoice.getTotalAmount());
    }
}
