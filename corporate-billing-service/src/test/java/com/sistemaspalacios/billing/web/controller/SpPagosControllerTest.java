package com.sistemaspalacios.billing.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.service.SpPagosQueryService;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpPagosControllerTest {

    private MockMvc mockMvc;
    private SpPagosQueryService queryService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        queryService = Mockito.mock(SpPagosQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SpPagosController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private FacturaSpPagosDto buildSampleDto(UUID id) {
        return new FacturaSpPagosDto(
                id,
                "51993",
                "FESP",
                "FESP 51993",
                "F1",
                "2026-09",
                "720377e072ca996f78d1f873a16899a51af08fc5ca705c4ebdfa9841c2320214396cd348fac2e601082dce87023b26e3",
                "CONSOLIDADA",
                true,
                new FacturaSpPagosDto.ClienteSpPagosDto(
                        "1088970617-6",
                        "CRISTIAN FERNANDO BRAVO MUÑOZ",
                        "LA CRUZ - NUEVO PUNTO",
                        "crisfe8609@gmail.com",
                        "3135963672"
                ),
                new FacturaSpPagosDto.FechasSpPagosDto(
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 25),
                        false
                ),
                new FacturaSpPagosDto.TotalesSpPagosDto(
                        new BigDecimal("4785000.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("909150.00"),
                        new BigDecimal("5694150.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("5694150.00"),
                        "CORTE_CARTERA_SIIGO"
                ),
                new FacturaSpPagosDto.RetencionesSpPagosDto(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("5694150.00")
                ),
                List.of(
                        new FacturaSpPagosDto.ItemSpPagosDto(
                                1,
                                "0100040000021",
                                "LA CRUZ.INT.FIB.DEDI.1.65 GB",
                                BigDecimal.ONE,
                                "UN",
                                new BigDecimal("2722500.00"),
                                new BigDecimal("2722500.00"),
                                new BigDecimal("19"),
                                new BigDecimal("517275.00"),
                                new BigDecimal("3239775.00")
                        )
                ),
                new FacturaSpPagosDto.EnlacesSpPagosDto(
                        true,
                        "/api/v1/facturas/" + id + "/pdf",
                        "/api/v1/facturas/" + id + "/representacion-grafica"
                ),
                "CREDITO",
                "Factura emitida en Siigo e importada para pago corporativo."
        );
    }

    @Test
    @DisplayName("GET /api/v1/pasarela/facturas/{id} retorna el JSON estructurado para SP Pagos")
    void testGetInvoiceForPayment() throws Exception {
        UUID id = UUID.randomUUID();
        FacturaSpPagosDto dto = buildSampleDto(id);
        when(queryService.getInvoiceForPayment(id)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/pasarela/facturas/" + id)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.numeroCompleto").value("FESP 51993"))
                .andExpect(jsonPath("$.cliente.nit").value("1088970617-6"))
                .andExpect(jsonPath("$.cliente.razonSocial").value("CRISTIAN FERNANDO BRAVO MUÑOZ"))
                .andExpect(jsonPath("$.importes.totalOficial").value(5694150.00))
                .andExpect(jsonPath("$.importes.saldoPendiente").value(5694150.00))
                .andExpect(jsonPath("$.pagable").value(true))
                .andExpect(jsonPath("$.enlaces.tienePdf").value(true))
                .andExpect(jsonPath("$.enlaces.pdfUrl").value("/api/v1/facturas/" + id + "/pdf"));
    }

    @Test
    @DisplayName("GET /api/v1/pasarela/facturas con NIT lista facturas pendientes para liquidar en pasarela")
    void testListInvoicesForPaymentWithNit() throws Exception {
        UUID id = UUID.randomUUID();
        FacturaSpPagosDto dto = buildSampleDto(id);
        when(queryService.listInvoicesForPayment(eq("1088970617-6"), any(), eq(true)))
                .thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/pasarela/facturas")
                        .param("nit", "1088970617-6")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numeroFactura").value("51993"))
                .andExpect(jsonPath("$[0].importes.saldoPendiente").value(5694150.00));
    }

    @Test
    @DisplayName("GET /api/v1/pasarela/facturas sin NIT deniega acceso cuando el rol es ROL_PASARELA")
    void testListInvoicesWithoutNitFailsForPasarelaRole() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                "pasarela-user", "secret", List.of(new SimpleGrantedAuthority("ROL_PASARELA")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            mockMvc.perform(get("/api/v1/pasarela/facturas")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("GET /api/v1/pasarela/facturas/cliente/{nit}/vigente retorna factura vigente del periodo")
    void testGetCurrentInvoiceForClientPeriod() throws Exception {
        UUID id = UUID.randomUUID();
        FacturaSpPagosDto dto = buildSampleDto(id);
        when(queryService.getCurrentInvoiceForClientPeriod("1088970617-6", "2026-09"))
                .thenReturn(dto);

        mockMvc.perform(get("/api/v1/pasarela/facturas/cliente/1088970617-6/vigente")
                        .param("periodo", "2026-09")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").value("2026-09"))
                .andExpect(jsonPath("$.cliente.nit").value("1088970617-6"));
    }
}
