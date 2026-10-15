package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.OrderUpdateType;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioCuotaInstalacion;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncResultDto;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class OrderSyncIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioNovedad repositorioNovedad;

    @Autowired
    private RepositorioCuotaInstalacion repositorioCuotaInstalacion;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repositorioSede.findByExternalOrderCodeAndOrigin("ORD-ANDINO-01", OrderOrigin.SPE).ifPresent(s -> {
            s.setExternalOrderCode("ORD-SIIGO-BATCH-01");
            repositorioSede.saveAndFlush(s);
        });
    }

    @Test
    @DisplayName("HU-01, HU-02: Sincronización inicial y reejecución idempotente (no duplica clientes, sedes ni novedades)")
    void testIdempotentSyncDoesNotDuplicate() {
        String nit = "900999888";
        String periodCode = "2026-08";

        SubscriptionOrderDto site1 = new SubscriptionOrderDto(
                OrderOrigin.SPE,
                nit,
                "Corporativo Andino S.A.S.",
                "ORD-ANDINO-01",
                "NIC-A01",
                "Sede Principal",
                "Cra 27 # 14-20",
                BigDecimal.valueOf(500000),
                LocalDate.of(2026, 8, 1),
                (short) 1,
                (short) 1,
                BillingType.F1,
                "Pasto",
                true,
                "facturacion@andino.com",
                "3115554433",
                BigDecimal.valueOf(600000),
                3,
                BigDecimal.valueOf(200000)
        );

        SubscriptionOrderDto site2 = new SubscriptionOrderDto(
                OrderOrigin.SPE,
                nit,
                "Corporativo Andino S.A.S.",
                "ORD-ANDINO-02",
                "NIC-A02",
                "Sede Norte",
                "Av Panamericana # 40-10",
                BigDecimal.valueOf(350000),
                LocalDate.of(2026, 8, 10),
                (short) 1,
                (short) 1,
                BillingType.F1,
                "Pasto",
                true,
                "facturacion@andino.com",
                "3115554433",
                null, null, null
        );

        OrderUpdateDto update1 = new OrderUpdateDto(
                "ORD-ANDINO-02",
                OrderOrigin.SPE,
                OrderUpdateType.ALTA,
                periodCode,
                BigDecimal.valueOf(350000),
                LocalDate.of(2026, 8, 10),
                true,
                "Alta técnica de sede norte"
        );

        // First execution
        OrderSyncResultDto firstResult = orderService.syncPeriod(periodCode, List.of(site1, site2), List.of(update1));

        assertEquals(2, firstResult.getClientsProcessed());
        assertEquals(1, firstResult.getClientsCreated());
        assertEquals(2, firstResult.getSitesProcessed());
        assertEquals(2, firstResult.getSitesCreated());
        assertEquals(1, firstResult.getUpdatesProcessed());
        assertEquals(0, firstResult.getErrorCount());

        // Verify database state after first execution
        Optional<CorporateClient> clientOpt = repositorioCliente.findByNit(nit);
        assertTrue(clientOpt.isPresent());
        CorporateClient client = clientOpt.get();

        List<SubscriptionSite> sites = repositorioSede.findByClientId(client.getId());
        assertEquals(2, sites.size());

        Optional<SubscriptionSite> site1EntityOpt = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-ANDINO-01", OrderOrigin.SPE);
        assertTrue(site1EntityOpt.isPresent());
        List<InstallationFee> fees = repositorioCuotaInstalacion.findBySiteIdAndStatus(
                site1EntityOpt.get().getId(), InstallationFeeStatus.PENDING);
        assertEquals(1, fees.size());
        assertEquals(Money.of(600000), fees.get(0).getTotalAmount());
        assertEquals(3, fees.get(0).getTotalInstallments());

        // Second execution with identical data: IDEMPOTENCE GUARANTEE
        OrderSyncResultDto secondResult = orderService.syncPeriod(periodCode, List.of(site1, site2), List.of(update1));

        assertEquals(2, secondResult.getClientsProcessed());
        assertEquals(0, secondResult.getClientsCreated(), "No new clients should be created on re-sync");
        assertEquals(2, secondResult.getSitesProcessed());
        assertEquals(0, secondResult.getSitesCreated(), "No new sites should be created on re-sync");
        assertEquals(1, secondResult.getUpdatesProcessed());
        assertEquals(0, secondResult.getErrorCount());

        // Assert database records remain unique and not multiplied
        List<SubscriptionSite> sitesAfterResync = repositorioSede.findByClientId(client.getId());
        assertEquals(2, sitesAfterResync.size(), "Total sites for client must remain 2");

        List<InstallationFee> feesAfterResync = repositorioCuotaInstalacion.findBySiteIdAndStatus(
                site1EntityOpt.get().getId(), InstallationFeeStatus.PENDING);
        assertEquals(1, feesAfterResync.size(), "Installation fee must not be duplicated");

        Optional<BillingPeriod> periodOpt = repositorioPeriodo.findByCode(periodCode);
        assertTrue(periodOpt.isPresent());
        List<OrderUpdate> updatesAfterResync = repositorioNovedad.findByPeriodId(periodOpt.get().getId());
        assertEquals(1, updatesAfterResync.size(), "Novelty updates must not be duplicated");
    }

    @Test
    @DisplayName("HU-02, HU-05: Re-sincronización actualiza datos modificados en la sede existente")
    void testSyncUpdatesExistingSiteData() {
        String nit = "900888777";
        String periodCode = "2026-08";

        SubscriptionOrderDto initial = new SubscriptionOrderDto(
                OrderOrigin.MEGA_RED,
                nit,
                "Mega Mayorista S.A.",
                "ORD-MEGA-99",
                "NIC-M99",
                "Central Mega",
                "Calle 10 # 5-20",
                BigDecimal.valueOf(1000000),
                LocalDate.of(2026, 8, 1),
                (short) 2,
                (short) 1,
                BillingType.F20,
                "Pasto",
                false,
                "admin@megared.com",
                "3009991122",
                null, null, null
        );

        orderService.syncPeriod(periodCode, List.of(initial), List.of());

        SubscriptionSite site = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-MEGA-99", OrderOrigin.MEGA_RED).orElseThrow();
        assertEquals(Money.of(1000000), site.getMonthlyValue());
        assertEquals("Calle 10 # 5-20", site.getAddress());

        // Update plan monthly value to 1,500,000 and change address
        SubscriptionOrderDto updated = new SubscriptionOrderDto(
                OrderOrigin.MEGA_RED,
                nit,
                "Mega Mayorista S.A.",
                "ORD-MEGA-99",
                "NIC-M99",
                "Central Mega Actualizada",
                "Carrera 20 # 30-40",
                BigDecimal.valueOf(1500000),
                LocalDate.of(2026, 8, 1),
                (short) 2,
                (short) 1,
                BillingType.F20,
                "Pasto",
                false,
                "admin@megared.com",
                "3009991122",
                null, null, null
        );

        orderService.syncPeriod(periodCode, List.of(updated), List.of());

        SubscriptionSite reloadedSite = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-MEGA-99", OrderOrigin.MEGA_RED).orElseThrow();
        assertEquals(site.getId(), reloadedSite.getId(), "Must update the same entity instance");
        assertEquals(Money.of(1500000), reloadedSite.getMonthlyValue(), "Monthly value must be updated");
        assertEquals("Carrera 20 # 30-40", reloadedSite.getAddress(), "Address must be updated");
        assertEquals("Central Mega Actualizada", reloadedSite.getSiteName());
    }

    @Test
    @DisplayName("HU-04: Consulta de novedades por periodo y sede")
    void testQueryUpdatesByPeriodAndSite() {
        String nit = "900777666";
        String periodCode = "2026-08";

        SubscriptionOrderDto siteDto = new SubscriptionOrderDto(
                OrderOrigin.ENLACES,
                nit,
                "Enlaces de Nariño",
                "ORD-ENL-10",
                "NIC-E10",
                "Sede Ipiales",
                "Cra 6 # 12-30",
                BigDecimal.valueOf(800000),
                LocalDate.of(2026, 8, 1),
                (short) 3,
                (short) 3,
                BillingType.F1,
                "Ipiales",
                false,
                "enlaces@corp.com",
                "3158887766",
                null, null, null
        );

        OrderUpdateDto updateDto = new OrderUpdateDto(
                "ORD-ENL-10",
                OrderOrigin.ENLACES,
                OrderUpdateType.CAMBIO_PLAN,
                periodCode,
                BigDecimal.valueOf(950000),
                LocalDate.of(2026, 8, 15),
                true,
                "Upgrade de ancho de banda"
        );

        orderService.syncPeriod(periodCode, List.of(siteDto), List.of(updateDto));

        BillingPeriod period = repositorioPeriodo.findByCode(periodCode).orElseThrow();
        SubscriptionSite site = repositorioSede.findByExternalOrderCodeAndOrigin("ORD-ENL-10", OrderOrigin.ENLACES).orElseThrow();

        List<OrderUpdate> updatesByPeriod = orderService.getUpdatesByPeriod(periodCode);
        assertFalse(updatesByPeriod.isEmpty());
        assertTrue(updatesByPeriod.stream().anyMatch(u -> u.getSiteId().equals(site.getId())));

        List<OrderUpdate> updatesBySite = orderService.getUpdatesByPeriodAndSite(period.getId(), site.getId());
        assertEquals(1, updatesBySite.size());
        assertEquals(OrderUpdateType.CAMBIO_PLAN, updatesBySite.get(0).getUpdateType());
        assertEquals("Upgrade de ancho de banda", updatesBySite.get(0).getObservation());
    }
}
