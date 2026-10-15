package com.sistemaspalacios.billing.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sistemaspalacios.billing.web.dto.GenerarFacturaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.repository.RepositorioFactura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class InvoiceControllerIntegrationTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private BillingService billingService;

    @Autowired
    private com.sistemaspalacios.billing.service.CreditNoteService creditNoteService;

    private BillingPeriod period;
    private CorporateClient client;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new InvoiceController(billingService, creditNoteService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        period = repositorioPeriodo.findByCode("2026-08").orElseGet(() -> {
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

        client = repositorioCliente.findByNit("900163149-2").orElseGet(() -> {
            CorporateClient c = new CorporateClient(
                    UUID.randomUUID(),
                    "900163149-2",
                    "CLIENTE CONTROLLER TEST S.A.S.",
                    "CL 2 22 75 BRR BACHUE",
                    "facturacion@test.com",
                    "3104005873",
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
                "ORD-CTRL-01",
                OrderOrigin.SPE,
                "NIC-CTRL-01",
                "INTERNET DEDICADO PASTO",
                "PASTO",
                Money.of(new BigDecimal("1500000.00")),
                LocalDate.of(2026, 1, 1),
                true
        );
        repositorioSede.saveAndFlush(site);
    }

    @Test
    @DisplayName("REST POST /api/v1/facturas/generar genera factura y reintento devuelve 409 Conflict")
    void testGenerarFacturaEndpoint() throws Exception {
        GenerarFacturaRequest request = new GenerarFacturaRequest(
                "2026-08",
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        // 1. Primera llamada exitosa -> 201 Created
        mockMvc.perform(post("/api/v1/facturas/generar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generadas", is(1)))
                .andExpect(jsonPath("$.facturas", hasSize(1)))
                .andExpect(jsonPath("$.facturas[0].nit", is(client.getNit())))
                .andExpect(jsonPath("$.facturas[0].numeroFactura", notNullValue()));

        // 2. Segunda llamada -> 409 Conflict por factura activa ya existente
        mockMvc.perform(post("/api/v1/facturas/generar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo", is("FACTURA_ACTIVA_YA_EXISTE")))
                .andExpect(jsonPath("$.nit", is(client.getNit())))
                .andExpect(jsonPath("$.periodo", is("2026-08")));
    }

    @Test
    @DisplayName("REST GET /api/v1/facturas y GET /api/v1/facturas/{id} retornan DTOs correctos")
    void testListarYDetalleFactura() throws Exception {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                "2026-08",
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        // Listar
        mockMvc.perform(get("/api/v1/facturas")
                        .param("periodo", "2026-08")
                        .param("nit", client.getNit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].nit", is(client.getNit())));

        // Detalle
        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(invoice.getId().toString())))
                .andExpect(jsonPath("$.cliente.nit", is(client.getNit())))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totales.total", notNullValue()));

        // Representación Gráfica HTML fiel a F001000000519930000.PDF
        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/representacion-grafica"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("SP SISTEMAS PALACIOS LTDA")))
                .andExpect(content().string(containsString("FACTURA ELECTRÓNICA DE VENTA")))
                .andExpect(content().string(containsString(client.getNit())));
    }

    @Test
    @DisplayName("HU-19: REST POST /api/v1/facturas/{id}/anular marca factura como ANULADA y registra motivo")
    void testAnularFacturaEndpoint() throws Exception {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                "2026-08",
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        mockMvc.perform(post("/api/v1/facturas/" + invoice.getId() + "/anular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("motivo", "Error de facturación corregido"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(invoice.getId().toString())))
                .andExpect(jsonPath("$.estado", is("ANULADA")))
                .andExpect(jsonPath("$.motivo", is("Error de facturación corregido")))
                .andExpect(jsonPath("$.anuladoEn", notNullValue()));

        // Intentar anular nuevamente debe retornar 400 Bad Request
        mockMvc.perform(post("/api/v1/facturas/" + invoice.getId() + "/anular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("motivo", "Reanulación"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo", is("SOLICITUD_INVALIDA")));
    }

    @Test
    @DisplayName("HU-20: REST POST /api/v1/facturas/{id}/notas-credito emite nota crédito y GET la lista")
    void testEmitirYConsultarNotaCreditoEndpoint() throws Exception {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                "2026-08",
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        com.sistemaspalacios.billing.web.dto.EmitirNotaCreditoRequest ncRequest =
                new com.sistemaspalacios.billing.web.dto.EmitirNotaCreditoRequest(
                        "Descuento comercial por baja de servicio",
                        "NC-TEST-001",
                        null
                );

        // 1. Emitir nota crédito total -> 201 Created
        mockMvc.perform(post("/api/v1/facturas/" + invoice.getId() + "/notas-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ncRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.facturaId", is(invoice.getId().toString())))
                .andExpect(jsonPath("$.tipo", is("CREDIT_NOTE")))
                .andExpect(jsonPath("$.numeroDocumento", is("NC-TEST-001")))
                .andExpect(jsonPath("$.motivo", is("Descuento comercial por baja de servicio")))
                .andExpect(jsonPath("$.valorNeto", notNullValue()))
                .andExpect(jsonPath("$.estado", is("APPLIED")))
                .andExpect(jsonPath("$.items", hasSize(1)));

        // 2. Consultar notas de crédito asociadas a la factura -> 200 OK
        mockMvc.perform(get("/api/v1/facturas/" + invoice.getId() + "/notas-credito"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].numeroDocumento", is("NC-TEST-001")))
                .andExpect(jsonPath("$[0].motivo", is("Descuento comercial por baja de servicio")));
    }

    @Test
    @DisplayName("HU-57, HU-60: Búsqueda avanzada con filtros, exclusión de residenciales por defecto y flag incluirResidenciales")
    void testAdvancedSearchAndResidentialExclusion() throws Exception {
        Invoice corpInvoice = billingService.generateConsolidatedInvoice(
                "2026-08",
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        // Crear una factura residencial manual con cliente independiente
        CorporateClient resClient = new CorporateClient(
                UUID.randomUUID(),
                "12345678",
                "RESIDENCIAL TEST",
                "CL RESIDENCIAL",
                "res@test.com",
                "3000000000",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "PASTO",
                null
        );
        repositorioCliente.saveAndFlush(resClient);

        Invoice resInvoice = new Invoice(
                UUID.randomUUID(),
                resClient.getId(),
                period.getId(),
                "FESP-RESIDENCIAL-99",
                LocalDate.of(2099, 1, 1),
                LocalDate.of(2099, 1, 15),
                BillingType.F1,
                InvoiceStatus.CONSOLIDATED
        );
        resInvoice.setResidential(true);
        resInvoice.setClientNit(resClient.getNit());
        resInvoice.setClientBusinessName("SIN_NOMBRE"); // Sin nombre disponible
        repositorioFactura.saveAndFlush(resInvoice);

        // 1. Consulta por defecto: No incluye residenciales
        mockMvc.perform(get("/api/v1/facturas").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[*].numeroFactura", not(hasItem("FESP-RESIDENCIAL-99"))));

        // 2. Consulta con incluirResidenciales=true: Aparece la residencial y con nombreDisponible=false
        mockMvc.perform(get("/api/v1/facturas").param("incluirResidenciales", "true").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].numeroFactura", hasItem("FESP-RESIDENCIAL-99")))
                .andExpect(jsonPath("$[?(@.numeroFactura == 'FESP-RESIDENCIAL-99')].nombreDisponible").value(hasItem(false)));

        // 3. Filtro por NIT
        mockMvc.perform(get("/api/v1/facturas").param("nit", client.getNit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nit").value(client.getNit()));

        // 4. Filtro por número de factura
        mockMvc.perform(get("/api/v1/facturas").param("numeroFactura", corpInvoice.getInvoiceNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].numeroFactura").value(corpInvoice.getInvoiceNumber()));
    }

    @Test
    @DisplayName("HU-58: Factura con saldo cero (cruzada con Nota Crédito) se excluye al filtrar soloConSaldo=true")
    void testCreditNoteBalanceExcludesZeroBalanceFromPending() throws Exception {
        Invoice invoice = billingService.generateConsolidatedInvoice(
                "2026-08",
                BillingType.F1,
                BillingCutType.ADVANCE,
                client.getNit()
        );

        // Emitir nota crédito por el 100% de la factura
        creditNoteService.issueCreditNote(
                invoice.getId(),
                "Anulación económica total",
                "NC-TOTAL-100",
                null
        );

        // Al consultar con soloConSaldo=true, la factura con saldo cero NO debe aparecer
        mockMvc.perform(get("/api/v1/facturas")
                        .param("nit", client.getNit())
                        .param("soloConSaldo", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.numeroFactura == '" + invoice.getInvoiceNumber() + "')]", empty()));
    }
}

