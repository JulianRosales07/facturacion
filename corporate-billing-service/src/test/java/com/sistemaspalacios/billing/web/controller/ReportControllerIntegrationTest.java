package com.sistemaspalacios.billing.web.controller;

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
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class ReportControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private ReportService reportService;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioSede repositorioSede;

    private BillingPeriod testPeriod;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportController(reportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        testPeriod = repositorioPeriodo.findByCode("2026-07").orElseGet(() -> {
            BillingPeriod p = new BillingPeriod(
                    UUID.randomUUID(),
                    "2026-07",
                    BillingCutType.ADVANCE,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    true
            );
            return repositorioPeriodo.saveAndFlush(p);
        });

        CorporateClient client = repositorioCliente.findByNit("900111222-3").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900111222-3",
                    "REVENDEDOR MAYORISTA SAS",
                    "Calle 5 # 10-20",
                    "mayorista@telecom.com",
                    "3115554433",
                    (short) 1,
                    (short) 1,
                    BillingType.F1,
                    true,
                    "Pasto",
                    null
            );
            return repositorioCliente.saveAndFlush(c);
        });

        SubscriptionSite siteNic1 = new SubscriptionSite(
                UUID.randomUUID(),
                client.getId(),
                "ORD-NIC-1",
                OrderOrigin.SPE,
                "NIC-REV-001",
                "PUNTO CENTRAL",
                "Calle 1",
                Money.of(3_000_000),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(siteNic1);

        SubscriptionSite siteNic2 = new SubscriptionSite(
                UUID.randomUUID(),
                client.getId(),
                "ORD-NIC-2",
                OrderOrigin.SPE,
                "NIC-REV-002",
                "PUNTO NORTE",
                "Calle 2",
                Money.of(1_500_000),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(siteNic2);

        Invoice inv = new Invoice(
                UUID.randomUUID(),
                client.getId(),
                testPeriod.getId(),
                "FESP-NIC-001",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 16),
                BillingType.F1,
                InvoiceStatus.CONSOLIDATED
        );
        inv.setClientNit(client.getNit());
        inv.setClientBusinessName(client.getBusinessName());

        inv.addItem(new InvoiceItem(
                null,
                inv.getId(),
                com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                siteNic1.getId(),
                "SERVICIO PUNTO CENTRAL",
                BigDecimal.ONE,
                Money.of(3_000_000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        ));

        inv.addItem(new InvoiceItem(
                null,
                inv.getId(),
                com.sistemaspalacios.billing.domain.model.BillableItemType.CORPORATE_SERVICE,
                siteNic2.getId(),
                "SERVICIO PUNTO NORTE",
                BigDecimal.ONE,
                Money.of(1_500_000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        ));

        inv.recalculateTotals();
        repositorioFactura.saveAndFlush(inv);
    }

    @Test
    @DisplayName("HU-59: GET /api/v1/reportes/nic?periodo= agrupa facturado por NIC para mayoristas")
    void testGetBillingByNicReport() throws Exception {
        mockMvc.perform(get("/api/v1/reportes/nic").param("periodo", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").value("2026-07"))
                .andExpect(jsonPath("$.totalNics").value(2))
                .andExpect(jsonPath("$.granTotalFacturado").value(5355000.00)) // (3M + 1.5M) * 1.19 = 5,355,000
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].nic").value("NIC-REV-001"))
                .andExpect(jsonPath("$.items[0].totalFacturado").value(3570000.00)) // 3M * 1.19
                .andExpect(jsonPath("$.items[1].nic").value("NIC-REV-002"))
                .andExpect(jsonPath("$.items[1].totalFacturado").value(1785000.00)); // 1.5M * 1.19
    }
}
