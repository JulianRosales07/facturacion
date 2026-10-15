package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.BillingService;
import com.sistemaspalacios.billing.service.CreditNoteService;
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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class CreditNoteControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private BillingService billingService;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioSede repositorioSede;

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new CreditNoteController(creditNoteService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        BillingPeriod period = repositorioPeriodo.findByCode("2026-08").orElseGet(() -> {
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

        CorporateClient client = repositorioCliente.findByNit("900888777-5").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900888777-5",
                    "CLIENTE TEST NOTAS S.A.S.",
                    "CARRERA 5 # 10-20",
                    "facturacion@clientenotas.com",
                    "3115554433",
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
                "ORD-NC-CTRL-01",
                OrderOrigin.SPE,
                "NIC-NC-CTRL-01",
                "INTERNET FIBRA OPTICA 100MB",
                "PASTO",
                Money.of(new BigDecimal("1200000.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(site);

        this.invoice = billingService.generateConsolidatedInvoice(
                period.getCode(),
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );
    }

    @Test
    @DisplayName("REST GET /api/v1/notas-credito/{id} retorna el detalle de la nota de crédito")
    void testGetCreditNoteById() throws Exception {
        AdjustmentDocument nc = creditNoteService.issueCreditNote(
                invoice.getId(),
                "Ajuste por soporte técnico prolongado",
                "NC-900888-01",
                null
        );

        mockMvc.perform(get("/api/v1/notas-credito/" + nc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(nc.getId().toString())))
                .andExpect(jsonPath("$.facturaId", is(invoice.getId().toString())))
                .andExpect(jsonPath("$.numeroDocumento", is("NC-900888-01")))
                .andExpect(jsonPath("$.motivo", is("Ajuste por soporte técnico prolongado")))
                .andExpect(jsonPath("$.estado", is("APPLIED")));
    }

    @Test
    @DisplayName("REST GET /api/v1/notas-credito/{id} con ID inexistente retorna 404 Not Found")
    void testGetCreditNoteNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/notas-credito/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo", is("RECURSO_NO_ENCONTRADO")));
    }
}
