package com.sistemaspalacios.billing.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerIntegrationTest {

    private MockMvc mockMvc;
    private PaymentService paymentService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        paymentService = Mockito.mock(PaymentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService, new SelectionAuditResolver(Mockito.mock(com.sistemaspalacios.billing.service.access.CurrentAccessService.class))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("HU-51: POST /api/v1/pagos/simular retorna cálculo de retenciones y valor neto")
    void testSimularPagoEndpoint() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        PaymentService.PaymentSimulationResult mockResult = new PaymentService.PaymentSimulationResult(
                "900111222",
                "CLIENTE TEST",
                List.of(),
                Money.of(119_000),
                Money.of(100_000),
                Money.of(19_000),
                List.of(new PaymentService.WithholdingLine(UUID.randomUUID(), "ReteICA Pasto 0,6%", "RETEICA",
                        new java.math.BigDecimal("0.60"), 2, Money.of(100_000), Money.ZERO, true, null, Money.of(600))),
                Money.of(600),
                Money.of(118_400),
                Money.ZERO,
                Money.of(118_400)
        );

        when(paymentService.simulate(any(), any())).thenReturn(mockResult);

        PaymentController.SimulatePaymentRequest request = new PaymentController.SimulatePaymentRequest(
                "900111222",
                List.of(invoiceId),
                List.of()
        );

        mockMvc.perform(post("/api/v1/pagos/simular")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value("900111222"))
                .andExpect(jsonPath("$.totalFacturas").value(119000.00))
                .andExpect(jsonPath("$.retenciones[0].tipo").value("RETEICA"))
                .andExpect(jsonPath("$.retenciones[0].aplica").value(true))
                .andExpect(jsonPath("$.retenciones[0].valorRetenido").value(600.00))
                .andExpect(jsonPath("$.totalRetenciones").value(600.00))
                .andExpect(jsonPath("$.netoAntesRedondeo").value(118400.00))
                .andExpect(jsonPath("$.ajusteRedondeo").value(0.00))
                .andExpect(jsonPath("$.netoAPagar").value(118400.00));
    }

    @Test
    @DisplayName("HU-51: /simular acepta el contrato del pagador {nit, invoiceIds, selectedConceptIds}")
    void simulateAcceptsGatewayContract() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID conceptId = UUID.randomUUID();
        when(paymentService.simulate(any(), any())).thenReturn(new PaymentService.PaymentSimulationResult("900123456", null, List.of(),
                Money.of(119_000), Money.of(100_000), Money.of(19_000), List.of(), Money.ZERO, Money.of(119_000), Money.ZERO, Money.of(119_000)));

        mockMvc.perform(post("/api/v1/pagos/simular").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nit":"900123456","applicationMode":"CONSOLIDATED","invoiceIds":["%s"],"selectedConcepts":[{"conceptId":"%s","percentage":3.5}]}""".formatted(invoiceId, conceptId)))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(PaymentService.PaymentSimulationCommand.class);
        Mockito.verify(paymentService).simulate(captor.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals("900123456", captor.getValue().clientNit());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(new PaymentService.ConceptSelection(conceptId, new java.math.BigDecimal("3.5"))),
                captor.getValue().selectedConcepts());
    }

    @Test
    @DisplayName("CA-11: /simular recibe el modo PER_INVOICE con la selección de cada factura")
    void simulateAcceptsPerInvoicePayload() throws Exception {
        UUID invoiceA = UUID.randomUUID();
        UUID invoiceB = UUID.randomUUID();
        UUID reteFuente = UUID.randomUUID();
        when(paymentService.simulate(any(), any())).thenReturn(new PaymentService.PaymentSimulationResult("800092198", null, List.of(),
                Money.ZERO, Money.ZERO, Money.ZERO, List.of(), Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                com.sistemaspalacios.billing.domain.model.RetentionApplicationMode.PER_INVOICE));

        mockMvc.perform(post("/api/v1/pagos/simular").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nit":"800092198","applicationMode":"PER_INVOICE","invoices":[
                          {"invoiceId":"%s","selectedConcepts":[{"conceptId":"%s","percentage":4}]},
                          {"invoiceId":"%s","selectedConcepts":[]}]}""".formatted(invoiceA, reteFuente, invoiceB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationMode").value("PER_INVOICE"));

        var captor = org.mockito.ArgumentCaptor.forClass(PaymentService.PaymentSimulationCommand.class);
        Mockito.verify(paymentService).simulate(captor.capture(), any());
        var command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(com.sistemaspalacios.billing.domain.model.RetentionApplicationMode.PER_INVOICE,
                command.applicationMode());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                new PaymentService.InvoiceRetentionSelection(invoiceA, List.of(new PaymentService.ConceptSelection(reteFuente, new java.math.BigDecimal("4")))),
                new PaymentService.InvoiceRetentionSelection(invoiceB, List.of())), command.invoiceSelections());
    }

    @Test
    @DisplayName("El formato anterior (solo ids, sin porcentaje) se rechaza en vez de liquidar sin retenciones")
    void legacyConceptIdsWithoutPercentageAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/pagos/simular").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nit":"900123456","applicationMode":"CONSOLIDATED","invoiceIds":["%s"],"selectedConceptIds":["%s"]}"""
                        .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("Seguridad: sin rol de analista no se aceptan ajustes manuales de retención")
    void payerCannotSendManualDeductions() throws Exception {
        mockMvc.perform(post("/api/v1/pagos/simular").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nit":"900123456","invoiceIds":["%s"],"selectedConceptIds":[],
                         "deductions":[{"validityId":"%s","amount":1,"reason":"x"}]}""".formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("Caso I: /checkout ignora el neto enviado por el cliente; el servicio solo recibe facturas y conceptos")
    void checkoutIgnoresClientAmounts() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentService.checkout(any(), any())).thenReturn(new PaymentService.CheckoutResult(paymentId, paymentId.toString(),
                "PENDIENTE", Money.of(118_400), null));

        mockMvc.perform(post("/api/v1/pagos/checkout").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nit":"900123456","applicationMode":"CONSOLIDATED","invoiceIds":["%s"],"selectedConcepts":[],"netoAPagar":1,"totalAmount":1}""".formatted(invoiceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pagoId").value(paymentId.toString()))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.netoAPagar").value(118400.00));

        var captor = org.mockito.ArgumentCaptor.forClass(PaymentService.CheckoutCommand.class);
        Mockito.verify(paymentService).checkout(captor.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(new PaymentService.CheckoutCommand("900123456", List.of(invoiceId), List.of(),
                        com.sistemaspalacios.billing.domain.model.RetentionApplicationMode.CONSOLIDATED, null),
                captor.getValue());
    }

    @Test
    @DisplayName("HU-53: POST /api/v1/pagos/ registrar pago correctamente procesado")
    void testRegistrarPagoSuccess() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentService.PaymentResult mockResult = new PaymentService.PaymentResult(
                paymentId,
                "900111222",
                LocalDate.now(),
                "TRANSFERENCIA",
                "11100501",
                Money.of(1_000_000),
                Money.of(40_000),
                Money.ZERO,
                Money.of(960_000),
                "TRX-001",
                List.of(UUID.randomUUID()),
                false,
                null,
                0,
                Money.ZERO
        );

        when(paymentService.register(any(), any())).thenReturn(mockResult);

        PaymentController.RegisterPaymentRequest request = new PaymentController.RegisterPaymentRequest(
                "900111222",
                List.of(UUID.randomUUID()),
                List.of(),
                LocalDate.now(),
                "TRANSFERENCIA",
                "11100501",
                "TRX-001",
                "Nota"
        );

        mockMvc.perform(post("/api/v1/pagos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.netPaidAmount").value(960000.00));
    }

    @Test
    @DisplayName("HU-55: GET /api/v1/pagos/{id}/recibo retorna HTML")
    void testGetReciboHtmlEndpoint() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.getReceiptHtml(paymentId)).thenReturn("<html><body>RECIBO DE CAJA RC-001</body></html>");

        mockMvc.perform(get("/api/v1/pagos/" + paymentId + "/recibo"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RECIBO DE CAJA RC-001")));
    }

    @Test
    @DisplayName("HU-55: GET /api/v1/pagos/{id}/interfaz-siigo descarga plano con encabezados correctos")
    void testGetInterfazSiigoEndpoint() throws Exception {
        UUID paymentId = UUID.randomUUID();
        byte[] flatBytes = "RC00100000001\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        when(paymentService.exportPaymentFlatFile(paymentId)).thenReturn(flatBytes);

        mockMvc.perform(get("/api/v1/pagos/" + paymentId + "/interfaz-siigo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("pago_siigo_")));
    }
}
