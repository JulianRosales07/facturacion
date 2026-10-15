package com.sistemaspalacios.billing.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.web.controller.TaxConfigurationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.sistemaspalacios.billing.service.ordenes.OrderSystemClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "auth.enabled=true"
})
@ActiveProfiles("postgres")
@Transactional
class SecurityEndpointsIntegrationTest {

    @TestConfiguration
    static class SecurityTestConfig {
        @Bean
        @Primary
        public JwtDecoder jwtDecoder() {
            return Mockito.mock(JwtDecoder.class);
        }

        @Bean
        @Primary
        public OrderSystemClient orderSystemClient() {
            OrderSystemClient mock = Mockito.mock(OrderSystemClient.class);
            Mockito.when(mock.fetchSubscriptions(Mockito.anyString())).thenReturn(List.of());
            Mockito.when(mock.fetchUpdates(Mockito.anyString())).thenReturn(List.of());
            return mock;
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void incomingInvoiceImportAndSyncRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/siigo/facturas/importar")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sincronizacion/ahora")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sincronizacion/ahora")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROL_PASARELA"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void panelSessionAndHistoryRequireAuthorizedCorporateIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/sesion")).andExpect(status().isUnauthorized());
        // La sesión solo describe la identidad del token: cualquier usuario autenticado puede consultarla.
        mockMvc.perform(get("/api/v1/sesion").with(jwt().authorities(new SimpleGrantedAuthority("ROL_PASARELA"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sesion").with(jwt().jwt(j->j.subject("operator-test").claim("name","Operador verificado"))
                .authorities(new SimpleGrantedAuthority("INVOICES_VIEW"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject",is("operator-test")))
                .andExpect(jsonPath("$.name",is("Operador verificado")));
        mockMvc.perform(get("/api/v1/siigo/facturas/historial")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/facturas/recibidas").with(jwt().authorities(new SimpleGrantedAuthority("INVOICES_VIEW"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.totalElements").isNumber());
    }

    // --- 1. Public endpoints (HU-37, RF-35) ---

    @Test
    @DisplayName("CA1 HU-37: /actuator/health is publicly accessible without authentication")
    void actuatorHealthShouldBePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA1 HU-37: /actuator/info is publicly accessible without authentication")
    void actuatorInfoShouldBePublic() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA2 HU-37: Non-health Actuator endpoints require authentication")
    void actuatorMetricsWithoutAuthReturns401() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.codigo", is("NO_AUTORIZADO")));
    }

    @Test
    @DisplayName("CA2 HU-37: Actuator metrics with business permissions only returns 403 Forbidden")
    void actuatorMetricsWithAnalystRoleReturns403() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority("INVOICES_VIEW"), new SimpleGrantedAuthority("ROL_ADMINISTRADOR"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.codigo", is("ACCESO_DENEGADO")));
    }

    @Test
    @DisplayName("CA2 HU-37: Actuator metrics with role SUPER_ADMIN is accessible")
    void actuatorMetricsWithAdminRoleIsAllowed() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk());
    }

    // --- 2. Unauthenticated access to business endpoints (HU-30 CA2) ---

    @Test
    @DisplayName("CA2 HU-30: Business endpoint /api/v1/facturas without token returns 401")
    void facturasWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/facturas"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.codigo", is("NO_AUTORIZADO")))
                .andExpect(jsonPath("$.mensaje", is("Token de autenticación ausente, expirado o inválido")));
    }

    @Test
    @DisplayName("Business endpoint /api/v1/lotes without token returns 401")
    void lotesWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/lotes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.codigo", is("NO_AUTORIZADO")));
    }

    // --- 3. Permission authorization tests (HU-32, HU-33, HU-35) ---

    @Test
    @DisplayName("CA1 HU-32: INVOICES_VIEW can list invoices")
    void analistaCanListInvoices() throws Exception {
        mockMvc.perform(get("/api/v1/facturas")
                        .with(jwt().authorities(new SimpleGrantedAuthority("INVOICES_VIEW"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA1 HU-32: BATCHES_VIEW can list export batches")
    void analistaCanListBatches() throws Exception {
        mockMvc.perform(get("/api/v1/lotes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("BATCHES_VIEW"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA1 HU-33: ROL_PASARELA can query invoices when specifying client NIT")
    void pasarelaCanQueryInvoicesWithNit() throws Exception {
        mockMvc.perform(get("/api/v1/facturas")
                        .param("nit", "900123456")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROL_PASARELA"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA1 HU-33: ROL_PASARELA cannot query general invoices without NIT")
    void pasarelaCannotQueryInvoicesWithoutNit() throws Exception {
        mockMvc.perform(get("/api/v1/facturas")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROL_PASARELA"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.codigo", is("ACCESO_DENEGADO")));
    }

    @Test
    @DisplayName("CA2 HU-33: ROL_PASARELA cannot generate export batches (returns 403)")
    void pasarelaCannotGenerateBatches() throws Exception {
        mockMvc.perform(post("/api/v1/lotes/generar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodo\":\"2026-08\",\"tipoFacturacion\":\"F1\",\"tipoCorte\":\"ADVANCE\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROL_PASARELA"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.codigo", is("ACCESO_DENEGADO")));
    }

    @Test
    @DisplayName("CA2 HU-33: ROL_PASARELA cannot generate invoices (returns 403)")
    void pasarelaCannotGenerateInvoices() throws Exception {
        mockMvc.perform(post("/api/v1/facturas/generar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodoCodigo\":\"2026-08\",\"tipoFacturacion\":\"F1\",\"tipoCorte\":\"ADVANCE\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROL_PASARELA"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.codigo", is("ACCESO_DENEGADO")));
    }

    @Test
    @DisplayName("CA1 HU-35: ROL_SISTEMA can trigger synchronization")
    void sistemaCanTriggerSync() throws Exception {
        mockMvc.perform(post("/api/v1/sincronizacion")
                        .param("periodo", "2026-08")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROL_SISTEMA"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA2 HU-32: TAX_CONCEPTS_VIEW cannot modify tax validities (returns 403)")
    void analistaCannotModifyTaxCatalog() throws Exception {
        TaxConfigurationController.TaxConceptValidityRequest req = new TaxConfigurationController.TaxConceptValidityRequest(
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(3.5),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        mockMvc.perform(post("/api/v1/impuestos/vigencias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(jwt().authorities(new SimpleGrantedAuthority("TAX_CONCEPTS_VIEW"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.codigo", is("ACCESO_DENEGADO")));
    }

    @Test
    @DisplayName("CA2 HU-32: TAX_CONCEPTS_MANAGE can create tax validities")
    void adminCanModifyTaxCatalog() throws Exception {
        TaxConfigurationController.TaxConceptValidityRequest req = new TaxConfigurationController.TaxConceptValidityRequest(
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(3.5),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        mockMvc.perform(post("/api/v1/impuestos/vigencias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(jwt().authorities(new SimpleGrantedAuthority("TAX_CONCEPTS_MANAGE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conceptCode", is("RETEFUENTE")))
                .andExpect(jsonPath("$.porcentajeMaximo", is(3.5)));
    }

    @Test
    void noveltyEndpointsRequireTheirPermissions() throws Exception {
        String body = "{\"nit\":\"900000001\",\"periodo\":\"2026-11\"}";
        // Consultar novedades: sin sesión 401; con permisos de facturas pero no de sincronización, 403.
        mockMvc.perform(get("/api/v1/sincronizacion/novedades/clientes/900000001")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sincronizacion/novedades/clientes/900000001")
                        .with(jwt().authorities(new SimpleGrantedAuthority("INVOICES_VIEW"))))
                .andExpect(status().isForbidden());
        // Reprocesar novedades modifica facturas: consultar no basta.
        mockMvc.perform(post("/api/v1/sincronizacion/consumir-novedades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sincronizacion/consumir-novedades").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SYNC_VIEW"))))
                .andExpect(status().isForbidden());
        // Detalle de cobros: solo con permiso de ver facturas.
        mockMvc.perform(get("/api/v1/facturas/" + java.util.UUID.randomUUID() + "/detalle-cobros")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SYNC_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyTrustedGatewayMayConfirmMoneyReceived() throws Exception {
        for (String endpoint : List.of("/api/v1/pagos/confirmar", "/api/v1/pagos/webhook")) {
            mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .with(jwt().authorities(new SimpleGrantedAuthority("INVOICES_VIEW"))))
                    .andExpect(status().isForbidden());
        }
    }
}
