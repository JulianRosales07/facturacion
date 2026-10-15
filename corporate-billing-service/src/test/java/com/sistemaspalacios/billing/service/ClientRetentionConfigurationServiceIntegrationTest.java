package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
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

/** Configuración de retenciones propia del cliente, guardada solo con su autorización. */
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class ClientRetentionConfigurationServiceIntegrationTest {

    private static final String NIT = "900333222-7";

    @Autowired private ClientRetentionConfigurationService service;
    @Autowired private RepositorioCliente clients;
    @Autowired private RepositorioVigenciaConcepto concepts;

    private TaxConceptValidity reteFuente;
    private TaxConceptValidity reteIca;

    @BeforeEach
    void setUp() {
        clients.findByNit(NIT).orElseGet(() -> clients.saveAndFlush(new CorporateClient(UUID.randomUUID(), NIT,
                "CLIENTE CONFIGURACION", "Calle 1", "pagos@cliente.co", "3000000000", (short) 1, (short) 1,
                BillingType.F1, false, "Pasto", null)));
        reteFuente = concepts.saveAndFlush(new TaxConceptValidity(null, "ReteFuente Servicios", 1, TaxConceptCode.RETEFUENTE,
                null, null, Money.of(150_000), new BigDecimal("11"), LocalDate.of(2026, 1, 1), null, true));
        reteIca = concepts.saveAndFlush(new TaxConceptValidity(null, "ReteICA Pasto", 1, TaxConceptCode.RETEICA,
                null, "Pasto", Money.ZERO, new BigDecimal("1.5"), LocalDate.of(2026, 1, 1), null, true));
    }

    private List<PaymentService.ConceptSelection> selection(String rf, String ica) {
        return List.of(new PaymentService.ConceptSelection(reteFuente.getId(), new BigDecimal(rf)),
                new PaymentService.ConceptSelection(reteIca.getId(), new BigDecimal(ica)));
    }

    @Test
    @DisplayName("Sin autorización del cliente no se guarda nada")
    void notSavedWithoutAuthorization() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(NIT, false, RetentionApplicationMode.CONSOLIDATED, selection("4", "0.6")));
        assertTrue(service.find(NIT).isEmpty());
    }

    @Test
    @DisplayName("Con autorización se guarda y se recupera para precargar futuros pagos; se puede reemplazar y eliminar")
    void savedWithAuthorizationAndReplaced() {
        var saved = service.save(NIT, true, RetentionApplicationMode.CONSOLIDATED, selection("4", "0.6"));
        assertEquals(RetentionApplicationMode.CONSOLIDATED, saved.applicationMode());
        assertNotNull(saved.authorizedAt());

        var found = service.find(NIT).orElseThrow();
        assertEquals(2, found.concepts().size());
        var rf = found.concepts().stream().filter(c -> c.conceptId().equals(reteFuente.getId())).findFirst().orElseThrow();
        assertEquals(0, rf.percentage().compareTo(new BigDecimal("4")));
        assertEquals(0, rf.maxPercentage().compareTo(new BigDecimal("11")));
        assertTrue(rf.usable());

        var replaced = service.save(NIT, true, RetentionApplicationMode.PER_INVOICE,
                List.of(new PaymentService.ConceptSelection(reteIca.getId(), new BigDecimal("1"))));
        assertEquals(RetentionApplicationMode.PER_INVOICE, replaced.applicationMode());
        assertEquals(1, service.find(NIT).orElseThrow().concepts().size(), "la nueva autorización reemplaza la anterior");

        service.delete(NIT);
        assertTrue(service.find(NIT).isEmpty());
    }

    @Test
    @DisplayName("Se valida igual que un pago: máximo del concepto, un concepto por tipo y modo obligatorio")
    void validatedLikeAPayment() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(NIT, true, RetentionApplicationMode.CONSOLIDATED, selection("12", "0.6")), "supera el máximo");
        var otherRf = concepts.saveAndFlush(new TaxConceptValidity(null, "ReteFuente Software", 1, TaxConceptCode.RETEFUENTE,
                null, null, Money.ZERO, new BigDecimal("4"), LocalDate.of(2026, 1, 1), null, true));
        assertThrows(IllegalArgumentException.class, () -> service.save(NIT, true, RetentionApplicationMode.CONSOLIDATED,
                List.of(new PaymentService.ConceptSelection(reteFuente.getId(), BigDecimal.ONE),
                        new PaymentService.ConceptSelection(otherRf.getId(), BigDecimal.ONE))), "dos ReteFuente");
        assertThrows(IllegalArgumentException.class, () -> service.save(NIT, true, null, selection("4", "0.6")), "sin modo");
    }

    @Test
    @DisplayName("Si el administrador baja el máximo, el porcentaje guardado deja de ser utilizable")
    void storedPercentageAboveNewMaximumIsNotUsable() {
        service.save(NIT, true, RetentionApplicationMode.CONSOLIDATED, selection("10", "0.6"));
        TaxConceptValidity lowered = new TaxConceptValidity(reteFuente.getId(), "ReteFuente Servicios", 1, TaxConceptCode.RETEFUENTE,
                null, null, Money.of(150_000), new BigDecimal("4"), LocalDate.of(2026, 1, 1), null, true);
        concepts.saveAndFlush(lowered);

        var rf = service.find(NIT).orElseThrow().concepts().stream()
                .filter(c -> c.conceptId().equals(reteFuente.getId())).findFirst().orElseThrow();
        assertFalse(rf.usable());
    }
}
