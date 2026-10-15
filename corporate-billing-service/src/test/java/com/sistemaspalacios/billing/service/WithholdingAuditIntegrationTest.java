package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.PaymentWithholding;
import com.sistemaspalacios.billing.domain.model.RetentionAuditEvent;
import com.sistemaspalacios.billing.domain.model.SelectionStatus;
import com.sistemaspalacios.billing.domain.model.SourceChannel;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.SelectionAudit;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioEventoRetencion;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioRetencionPago;
import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import com.sistemaspalacios.billing.service.ordenes.OrderSystemClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auditoría de porcentajes de retenciones: snapshot final (retencion_pago) e historial de
 * interacción (retencion_evento), con la seguridad activa para probar canal y actor reales.
 */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "auth.enabled=true"})
@ActiveProfiles("postgres")
@Import(WithholdingAuditIntegrationTest.Config.class)
@Transactional
class WithholdingAuditIntegrationTest {

    private static final String NIT = "800092198";

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        JwtDecoder auditTestJwtDecoder() {
            return Mockito.mock(JwtDecoder.class);
        }

        @Bean
        @Primary
        OrderSystemClient auditTestOrderSystemClient() {
            OrderSystemClient mock = Mockito.mock(OrderSystemClient.class);
            Mockito.when(mock.fetchSubscriptions(Mockito.anyString())).thenReturn(List.of());
            Mockito.when(mock.fetchUpdates(Mockito.anyString())).thenReturn(List.of());
            return mock;
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private PaymentService paymentService;
    @Autowired private RepositorioRetencionPago withholdings;
    @Autowired private RepositorioEventoRetencion events;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioCliente clients;
    @Autowired private RepositorioPeriodo periods;
    @Autowired private RepositorioVigenciaConcepto concepts;
    @Autowired private RepositorioUsuario users;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private CorporateClient client;
    private Invoice small;  // 100.000 + IVA: no alcanza la base mínima de ReteFuente
    private Invoice large;  // 500.000 + IVA
    private TaxConceptValidity reteFuente; // máximo 4%, base mínima 150.000
    private TaxConceptValidity reteIca;    // máximo 0,6%

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        BillingPeriod period = periods.findByCode("2026-09").orElseGet(() -> periods.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), "2026-09", BillingCutType.ADVANCE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), true)));
        BillingPeriod other = periods.findByCode("2026-08").orElseGet(() -> periods.saveAndFlush(
                new BillingPeriod(UUID.randomUUID(), "2026-08", BillingCutType.ADVANCE, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true)));
        client = clients.findByNit(NIT).orElseGet(() -> clients.saveAndFlush(new CorporateClient(UUID.randomUUID(), NIT,
                "CLIENTE AUDITORIA", "Calle 1", "pagos@cliente.co", "3000000000", (short) 1, (short) 1, BillingType.F1, true, "Pasto", null)));
        small = invoice("AUD-S-" + UUID.randomUUID().toString().substring(0, 6), period, 100_000);
        large = invoice("AUD-L-" + UUID.randomUUID().toString().substring(0, 6), other, 500_000);
        reteFuente = concepts.saveAndFlush(new TaxConceptValidity(null, "ReteFuente Servicios", 3, TaxConceptCode.RETEFUENTE, null, null,
                Money.of(150_000), new BigDecimal("4.00"), LocalDate.of(2026, 1, 1), null, true));
        reteIca = concepts.saveAndFlush(new TaxConceptValidity(null, "ReteICA Pasto", 1, TaxConceptCode.RETEICA, null, null,
                Money.ZERO, new BigDecimal("0.60"), LocalDate.of(2026, 1, 1), null, true));
    }

    private Invoice invoice(String number, BillingPeriod period, long subtotal) {
        Invoice invoice = new Invoice(UUID.randomUUID(), client.getId(), period.getId(), number,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), BillingType.F1, InvoiceStatus.CONSOLIDATED);
        invoice.setClientNit(NIT);
        invoice.setClientBusinessName(client.getBusinessName());
        invoice.addItem(new InvoiceItem(null, invoice.getId(), BillableItemType.CORPORATE_SERVICE, null, "SERVICIO",
                BigDecimal.ONE, Money.of(subtotal), BigDecimal.ZERO, true, BigDecimal.valueOf(19)));
        invoice.recalculateTotals();
        return repositorioFactura.saveAndFlush(invoice);
    }

    private static SelectionAudit gateway(String session) {
        return new SelectionAudit(SourceChannel.PAYMENT_GATEWAY, null, null, null, null, session, "190.1.2.3", "Chrome / Windows");
    }

    private PaymentService.CheckoutResult checkout(Invoice invoice, SelectionAudit audit, TaxConceptValidity concept, String percentage) {
        return paymentService.checkout(new PaymentService.CheckoutCommand(NIT, List.of(invoice.getId()),
                List.of(new PaymentService.ConceptSelection(concept.getId(), new BigDecimal(percentage)))), audit);
    }

    private PaymentWithholding snapshotOf(UUID paymentId) {
        withholdings.flush();
        return withholdings.findByPaymentId(paymentId).get(0);
    }

    private List<RetentionAuditEvent> history(String session) {
        events.flush();
        return events.findBySessionIdOrderByOccurredAtAsc(session);
    }

    private static RequestPostProcessor as(String username, String... authorities) {
        return jwt().jwt(j -> j.claim("preferred_username", username))
                .authorities(Arrays.stream(authorities).map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }

    @Test
    @DisplayName("Caso 1: el cliente selecciona ReteFuente 4% en la pasarela → 4%, selected_at, canal PAYMENT_GATEWAY, actor NIT")
    void case1_clientSelectsInGateway() throws Exception {
        String session = "sess-" + UUID.randomUUID();
        String body = "{\"nit\":\"" + NIT + "\",\"applicationMode\":\"CONSOLIDATED\",\"invoiceIds\":[\"" + large.getId() + "\"],"
                + "\"selectedConcepts\":[{\"conceptId\":\"" + reteFuente.getId() + "\",\"percentage\":4.0}]}";
        String response = mvc.perform(post("/api/v1/pagos/checkout").with(as("pasarela-client", "ROL_PASARELA"))
                        .header("X-Client-Channel", "PAYMENT_GATEWAY").header("X-Selection-Session", session)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("pagoId").asText());

        PaymentWithholding w = snapshotOf(paymentId);
        assertThat(w.getEnteredPercentage()).isEqualByComparingTo("4");
        assertThat(w.getAppliedPercentage()).isEqualByComparingTo("4");
        assertThat(w.getSelectedAt()).isNotNull();
        assertThat(w.getAppliedAt()).isNull();
        assertThat(w.getSourceChannel()).isEqualTo(SourceChannel.PAYMENT_GATEWAY);
        assertThat(w.getSelectedByNit()).isEqualTo(NIT);
        assertThat(w.getSelectedByUsername()).as("en la pasarela no se inventa un usuario").isNull();
        assertThat(w.getSelectionSessionId()).isEqualTo(session);
        assertThat(w.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(w.getUserAgent()).contains("Chrome");
        assertThat(w.getSelectionStatus()).isEqualTo(SelectionStatus.SELECTED);
        assertThat(w.getVersionNumber()).isEqualTo(3);
        assertThat(history(session)).extracting(e -> e.getType() + " " + e.getConceptCode() + " " + e.getNewPercentage().stripTrailingZeros().toPlainString())
                .containsExactly("SELECTED RETEFUENTE 4");
    }

    @Test
    @DisplayName("Un cliente de la pasarela no puede declararse ADMIN_WEB: queda como API sin usuario")
    void gatewayCannotSpoofAdminChannel() throws Exception {
        String body = "{\"nit\":\"" + NIT + "\",\"applicationMode\":\"CONSOLIDATED\",\"invoiceIds\":[\"" + large.getId() + "\"],"
                + "\"selectedConcepts\":[{\"conceptId\":\"" + reteIca.getId() + "\",\"percentage\":0.6}]}";
        String response = mvc.perform(post("/api/v1/pagos/checkout").with(as("pasarela-client", "ROL_PASARELA"))
                        .header("X-Client-Channel", "ADMIN_WEB").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        PaymentWithholding w = snapshotOf(UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("pagoId").asText()));
        assertThat(w.getSourceChannel()).isEqualTo(SourceChannel.API);
        assertThat(w.getSelectedByUsername()).isNull();
    }

    @Test
    @DisplayName("Caso 2: un analista registra ReteICA 0,6% desde el panel → ADMIN_WEB con su usuario, aplicado de inmediato")
    void case2_analystSelectsInAdmin() throws Exception {
        UserAccount analyst = new UserAccount("jperez", "jperez@empresa.com", "Juan", "Pérez", "prueba");
        users.saveAndFlush(analyst);
        String body = "{\"clientNit\":\"" + NIT + "\",\"applicationMode\":\"CONSOLIDATED\",\"invoiceIds\":[\"" + large.getId() + "\"],"
                + "\"selectedConcepts\":[{\"conceptId\":\"" + reteIca.getId() + "\",\"percentage\":0.6}],"
                + "\"paymentMethod\":\"TRANSFERENCIA\",\"bankAccount\":\"BANCOLOMBIA\",\"transactionNumber\":\"TX-AUD-" + UUID.randomUUID() + "\"}";
        String response = mvc.perform(post("/api/v1/pagos").with(as("jperez", "PAYMENTS_REGISTER"))
                        .header("X-Client-Channel", "ADMIN_WEB").header("X-Selection-Session", "admin-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("paymentId").asText());

        PaymentWithholding w = snapshotOf(paymentId);
        assertThat(w.getSourceChannel()).isEqualTo(SourceChannel.ADMIN_WEB);
        assertThat(w.getSelectedByUserId()).isEqualTo(analyst.getId());
        assertThat(w.getSelectedByUsername()).isEqualTo("jperez");
        assertThat(w.getSelectedByName()).isEqualTo("Juan Pérez");
        assertThat(w.getEnteredPercentage()).isEqualByComparingTo("0.6");
        assertThat(w.getSelectionStatus()).isEqualTo(SelectionStatus.APPLIED);
        assertThat(w.getAppliedAt()).isNotNull();
        assertThat(history(w.getSelectionSessionId())).extracting(e -> e.getType() + " " + e.getActorUsername())
                .containsExactly("SELECTED jperez", "APPLIED jperez");
    }

    @Test
    @DisplayName("Caso 3: el cliente cambia 4% → 3,5%: el historial conserva ambos y cada pago su propio snapshot")
    void case3_changeKeepsHistory() {
        String session = "sess-" + UUID.randomUUID();
        var first = checkout(large, gateway(session), reteFuente, "4");
        var second = checkout(large, gateway(session), reteFuente, "3.5");

        assertThat(history(session)).extracting(e -> e.getType() + " " + plain(e.getOldPercentage()) + "→" + plain(e.getNewPercentage()))
                .containsExactly("SELECTED -→4", "CANCELLED -→4", "UPDATED 4→3.5");
        // El 4% no se sobrescribe: queda en el checkout reemplazado (anulado); el nuevo pago tiene 3,5%.
        PaymentWithholding replaced = snapshotOf(first.paymentId());
        assertThat(replaced.getEnteredPercentage()).isEqualByComparingTo("4");
        assertThat(replaced.getSelectionStatus()).isEqualTo(SelectionStatus.CANCELLED);
        assertThat(snapshotOf(second.paymentId()).getEnteredPercentage()).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("Caso 4: ReteFuente no alcanza la base mínima → NOT_APPLICABLE con valor retenido 0")
    void case4_notApplicable() {
        String session = "sess-" + UUID.randomUUID();
        var result = checkout(small, gateway(session), reteFuente, "4");
        PaymentWithholding w = snapshotOf(result.paymentId());
        assertThat(w.getSelectionStatus()).isEqualTo(SelectionStatus.NOT_APPLICABLE);
        assertThat(w.getWithheldAmount()).isEqualTo(Money.ZERO);
        assertThat(w.isApplicable()).isFalse();
        assertThat(history(session)).extracting(RetentionAuditEvent::getType)
                .containsExactly(RetentionAuditEvent.Type.SELECTED, RetentionAuditEvent.Type.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("Caso 5: la pasarela confirma el pago → applied_at, estado APPLIED y evento APPLIED; selected_at no cambia")
    void case5_confirmedPayment() {
        String session = "sess-" + UUID.randomUUID();
        var result = checkout(large, gateway(session), reteFuente, "4");
        var selectedAt = snapshotOf(result.paymentId()).getSelectedAt();
        paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(NIT, "TX-" + UUID.randomUUID(), List.of(), List.of(),
                result.netPayable().amount(), LocalDate.now(), "PSE", "PSE", "AUTH", null, null, null, result.paymentId(), "APPROVED"));

        PaymentWithholding w = snapshotOf(result.paymentId());
        assertThat(w.getAppliedAt()).isNotNull().isAfterOrEqualTo(selectedAt);
        assertThat(w.getSelectedAt()).isEqualTo(selectedAt);
        assertThat(w.getSelectionStatus()).isEqualTo(SelectionStatus.APPLIED);
        assertThat(history(session)).extracting(e -> e.getType() + " " + e.getActorName())
                .containsExactly("SELECTED null", "APPLIED Pasarela de pagos (confirmación)");
    }

    @Test
    @DisplayName("Caso 6: un snapshot confirmado no se puede modificar (dominio)")
    void case6_confirmedSnapshotIsImmutableInDomain() {
        var result = checkout(large, gateway("sess-" + UUID.randomUUID()), reteFuente, "4");
        paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(NIT, "TX-" + UUID.randomUUID(), List.of(), List.of(),
                result.netPayable().amount(), LocalDate.now(), "PSE", "PSE", "AUTH", null, null, null, result.paymentId(), "APPROVED"));
        PaymentWithholding w = snapshotOf(result.paymentId());

        assertThatThrownBy(w::markCancelled).isInstanceOf(IllegalStateException.class).hasMessageContaining("ya fue aplicada");
        assertThatThrownBy(() -> w.recordSelection(BigDecimal.TEN, java.time.Instant.now(), gateway("x-12345678")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inmutable");
        assertThatThrownBy(() -> w.setAdjustmentReason("cambio")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Caso 6: ni con SQL directo se puede cambiar el porcentaje de un snapshot confirmado")
    void case6_confirmedSnapshotIsImmutableInDatabase() {
        var result = checkout(large, gateway("sess-" + UUID.randomUUID()), reteFuente, "4");
        paymentService.confirmPayment(new PaymentService.PaymentConfirmationCommand(NIT, "TX-" + UUID.randomUUID(), List.of(), List.of(),
                result.netPayable().amount(), LocalDate.now(), "PSE", "PSE", "AUTH", null, null, null, result.paymentId(), "APPROVED"));
        UUID id = snapshotOf(result.paymentId()).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE retencion_pago SET porcentaje = 10, valor_retenido = 0 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("inmutable");
    }

    @Test
    @DisplayName("El historial de interacción es de solo inserción")
    void eventsAreAppendOnly() {
        String session = "sess-" + UUID.randomUUID();
        checkout(large, gateway(session), reteFuente, "4");
        UUID eventId = history(session).get(0).getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE retencion_evento SET porcentaje_nuevo = 1 WHERE id = ?", eventId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("solo inserción");
    }

    @Test
    @DisplayName("Consulta de auditoría: por pago (hora de Bogotá) y búsqueda/exportación; exige WITHHOLDINGS_AUDIT_VIEW")
    void auditEndpoints() throws Exception {
        String session = "sess-" + UUID.randomUUID();
        var result = checkout(large, gateway(session), reteFuente, "4");
        withholdings.flush();

        mvc.perform(get("/api/v1/admin/payments/" + result.paymentId() + "/withholdings/audit").with(as("financiera", "PAYMENTS_VIEW")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/payments/" + result.paymentId() + "/withholdings/audit").with(as("auditor", "WITHHOLDINGS_AUDIT_VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientNit", is(NIT)))
                .andExpect(jsonPath("$.audit[0].conceptName", is("ReteFuente Servicios")))
                .andExpect(jsonPath("$.audit[0].enteredPercentage", is(4)))
                .andExpect(jsonPath("$.audit[0].conceptVersion", is(3)))
                .andExpect(jsonPath("$.audit[0].sourceChannel", is("PAYMENT_GATEWAY")))
                .andExpect(jsonPath("$.audit[0].selectedBy.label", is("NIT " + NIT)))
                .andExpect(jsonPath("$.audit[0].selectedAt", containsString("-05:00")))
                .andExpect(jsonPath("$.events[0].eventType", is("SELECTED")));

        mvc.perform(get("/api/v1/admin/withholdings/audit").with(as("auditor", "WITHHOLDINGS_AUDIT_VIEW"))
                        .param("invoice", "F1-" + large.getInvoiceNumber()).param("channel", "PAYMENT_GATEWAY").param("concept", "retefuente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(1)))
                .andExpect(jsonPath("$.items[0].invoiceNumbers[0]", is("F1-" + large.getInvoiceNumber())));

        mvc.perform(get("/api/v1/admin/withholdings/audit/export").with(as("auditor", "WITHHOLDINGS_AUDIT_VIEW"))
                        .param("format", "csv").param("paymentId", result.paymentId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ReteFuente Servicios")))
                .andExpect(content().string(containsString("PAYMENT_GATEWAY")));
    }

    @Test
    @DisplayName("Exportación a Excel: hojas Retenciones, Historial e Información con números y fechas tipados")
    void exportsExcel() throws Exception {
        String session = "sess-" + UUID.randomUUID();
        var result = checkout(large, gateway(session), reteFuente, "4");
        withholdings.flush();

        byte[] file = mvc.perform(get("/api/v1/admin/withholdings/audit/export").with(as("auditor", "WITHHOLDINGS_AUDIT_VIEW"))
                        .param("paymentId", result.paymentId().toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", containsString(".xlsx")))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();

        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Retenciones");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("Historial");
            assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("Información");

            var sheet = workbook.getSheet("Retenciones");
            var header = sheet.getRow(0);
            var row = sheet.getRow(1);
            java.util.function.Function<String, org.apache.poi.ss.usermodel.Cell> cell = name -> {
                for (var c : header) if (c.getStringCellValue().equals(name)) return row.getCell(c.getColumnIndex());
                throw new AssertionError("Columna " + name);
            };
            assertThat(cell.apply("Concepto").getStringCellValue()).isEqualTo("ReteFuente Servicios");
            assertThat(cell.apply("% ingresado").getNumericCellValue()).isEqualTo(4.0);
            assertThat(cell.apply("Valor retenido").getNumericCellValue()).isEqualTo(20_000.0);
            assertThat(cell.apply("Canal").getStringCellValue()).isEqualTo("Pasarela de pagos");
            assertThat(cell.apply("Actor").getStringCellValue()).isEqualTo("NIT " + NIT);
            assertThat(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell.apply("Seleccionado"))).isTrue();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();

            assertThat(workbook.getSheet("Historial").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Seleccionó");
            var info = workbook.getSheet("Información");
            assertThat(info.getRow(2).getCell(1).getStringCellValue()).isEqualTo("auditor");
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
