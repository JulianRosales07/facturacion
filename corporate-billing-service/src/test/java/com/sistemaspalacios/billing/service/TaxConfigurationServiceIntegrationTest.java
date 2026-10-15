package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.TaxConcept;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class TaxConfigurationServiceIntegrationTest {

    @Autowired
    private TaxConfigurationService taxConfigurationService;

    @Test
    @DisplayName("HU-50: Coexistencia de varios conceptos del mismo tipo y varias estampillas con versión y nombre")
    void testCoexistenceOfMultipleConceptsAndStamps() {
        // 1. Conceptos base
        List<TaxConcept> concepts = taxConfigurationService.listConcepts();
        assertFalse(concepts.isEmpty());

        // 2. Coexistencia de ReteFuente 4% servicios y 3.5% software
        TaxConceptValidity rfServicios = new TaxConceptValidity(
                null,
                "ReteFuente 4% Servicios Generales",
                1,
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(1000000),
                BigDecimal.valueOf(4.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        TaxConceptValidity rfSoftware = new TaxConceptValidity(
                null,
                "ReteFuente 3.5% Software y Licencias",
                1,
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(1500000),
                BigDecimal.valueOf(3.50),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        // 3. Múltiples estampillas
        TaxConceptValidity estampillaUniv = new TaxConceptValidity(
                null,
                "Estampilla Pro-Desarrollo Universidad",
                1,
                TaxConceptCode.ESTAMPILLA,
                ServiceClassification.INTERNET,
                null,
                Money.ZERO,
                BigDecimal.valueOf(1.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        TaxConceptValidity estampillaDeportes = new TaxConceptValidity(
                null,
                "Estampilla Pro-Deporte Municipal",
                1,
                TaxConceptCode.ESTAMPILLA,
                ServiceClassification.INTERNET,
                null,
                Money.ZERO,
                BigDecimal.valueOf(0.50),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        TaxConceptValidity savedRfServ = taxConfigurationService.createValidity(rfServicios);
        TaxConceptValidity savedRfSoft = taxConfigurationService.createValidity(rfSoftware);
        TaxConceptValidity savedEstUniv = taxConfigurationService.createValidity(estampillaUniv);
        TaxConceptValidity savedEstDep = taxConfigurationService.createValidity(estampillaDeportes);

        assertNotNull(savedRfServ.getId());
        assertNotNull(savedRfSoft.getId());
        assertNotNull(savedEstUniv.getId());
        assertNotNull(savedEstDep.getId());

        List<TaxConceptValidity> rfList = taxConfigurationService.findValiditiesByConceptAndDate(
                TaxConceptCode.RETEFUENTE, LocalDate.of(2026, 5, 1));
        assertTrue(rfList.size() >= 2);

        List<TaxConceptValidity> estList = taxConfigurationService.findValiditiesByConceptAndDate(
                TaxConceptCode.ESTAMPILLA, LocalDate.of(2026, 5, 1));
        assertTrue(estList.size() >= 2);
    }

    @Test
    @DisplayName("HU-50: Cambio de base crea versión nueva, cierra la anterior y nunca sobrescribe")
    void testCreateNewVersionClosesPreviousAndIncrementsVersion() {
        TaxConceptValidity v1 = new TaxConceptValidity(
                null,
                "ReteFuente Base 2026",
                1,
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(1000000),
                BigDecimal.valueOf(4.00),
                LocalDate.of(2026, 1, 1),
                null,
                true
        );
        TaxConceptValidity savedV1 = taxConfigurationService.createValidity(v1);

        // Crear nueva versión a partir del 1 de julio con nueva base mínima
        TaxConceptValidity v2 = taxConfigurationService.createNewVersion(
                savedV1.getId(),
                Money.of(1300000),
                BigDecimal.valueOf(4.00),
                LocalDate.of(2026, 7, 1)
        );

        assertNotNull(v2.getId());
        assertEquals(2, v2.getVersionNumber());
        assertEquals(Money.of(1300000), v2.getMinimumBase());
        assertEquals(LocalDate.of(2026, 7, 1), v2.getValidFrom());
        assertNull(v2.getValidTo());

        // La versión 1 debe haber quedado cerrada al 2026-06-30
        TaxConceptValidity v1Updated = taxConfigurationService.findValiditiesByConceptAndDate(
                TaxConceptCode.RETEFUENTE, LocalDate.of(2026, 3, 1))
                .stream()
                .filter(v -> v.getId().equals(savedV1.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(LocalDate.of(2026, 6, 30), v1Updated.getValidTo());
        assertEquals(Money.of(1000000), v1Updated.getMinimumBase());

        // Resolución en marzo 2026 devuelve v1 (base 1.000.000)
        List<TaxConceptValidity> march = taxConfigurationService.findValiditiesByConceptAndDate(
                TaxConceptCode.RETEFUENTE, LocalDate.of(2026, 3, 1));
        assertTrue(march.stream().anyMatch(v -> v.getMinimumBase().equals(Money.of(1000000))));

        // Resolución en agosto 2026 devuelve v2 (base 1.300.000)
        List<TaxConceptValidity> august = taxConfigurationService.findValiditiesByConceptAndDate(
                TaxConceptCode.RETEFUENTE, LocalDate.of(2026, 8, 1));
        assertTrue(august.stream().anyMatch(v -> v.getMinimumBase().equals(Money.of(1300000))));
    }

    private TaxConceptValidity validity(TaxConceptCode code, String name, String percentage, long minimumBase) {
        return new TaxConceptValidity(null, name, 1, code, null, null, Money.of(minimumBase), new BigDecimal(percentage),
                LocalDate.of(2026, 1, 1), null, true);
    }

    @Test
    @DisplayName("Editar corrige la vigencia en sitio: mismo id, mismo tipo y misma versión")
    void editUpdatesInPlaceKeepingTypeAndVersion() {
        TaxConceptValidity created = taxConfigurationService.createValidity(
                validity(TaxConceptCode.RETEFUENTE, "ReteFuente Servicios 4%", "4.00", 150_000));
        TaxConceptValidity v2 = taxConfigurationService.createNewVersion(created.getId(), null, new BigDecimal("3.50"), LocalDate.of(2026, 7, 1));

        // El formulario envía numeroVersion por omisión (1): la versión no debe retroceder.
        TaxConceptValidity edited = taxConfigurationService.updateValidity(v2.getId(), new TaxConceptValidity(v2.getId(),
                "ReteFuente Servicios 3,5% corregida", 1, TaxConceptCode.RETEFUENTE, null, null, Money.of(200_000),
                new BigDecimal("3.50"), LocalDate.of(2026, 7, 1), null, true));

        assertEquals(v2.getId(), edited.getId());
        assertEquals(2, edited.getVersionNumber());
        assertEquals(TaxConceptCode.RETEFUENTE, edited.getConceptCode());
        assertEquals("ReteFuente Servicios 3,5% corregida", edited.getName());
        assertEquals(Money.of(200_000), edited.getMinimumBase());
        assertEquals(2, taxConfigurationService.listAllValidities().stream()
                .filter(v -> v.getId().equals(created.getId()) || v.getId().equals(v2.getId())).count(), "editar no crea registros");
    }

    @Test
    @DisplayName("Editar y versionar rechazan datos inválidos")
    void invalidCatalogChangesAreRejected() {
        TaxConceptValidity created = taxConfigurationService.createValidity(
                validity(TaxConceptCode.RETEICA, "ReteICA Pasto 0,6%", "0.60", 0));

        assertThrows(IllegalArgumentException.class, () -> taxConfigurationService.updateValidity(created.getId(),
                validity(TaxConceptCode.RETEIVA, "Otro tipo", "0.60", 0)), "no se cambia el tipo de impuesto");
        assertThrows(IllegalArgumentException.class, () -> taxConfigurationService.updateValidity(created.getId(),
                validity(TaxConceptCode.RETEICA, "ReteICA", "120", 0)), "porcentaje mayor a 100");
        assertThrows(IllegalArgumentException.class, () -> taxConfigurationService.updateValidity(created.getId(),
                validity(TaxConceptCode.RETEICA, "ReteICA", "0.62555", 0)), "más de 4 decimales");
        assertThrows(IllegalArgumentException.class, () -> taxConfigurationService.updateValidity(created.getId(),
                new TaxConceptValidity(created.getId(), "ReteICA", 1, TaxConceptCode.RETEICA, null, null, Money.ZERO,
                        new BigDecimal("0.60"), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), true)), "termina antes de empezar");
        assertThrows(IllegalArgumentException.class, () -> taxConfigurationService.createNewVersion(created.getId(),
                null, new BigDecimal("0.70"), LocalDate.of(2026, 1, 1)), "una versión nueva no puede empezar el mismo día que la actual");
    }
}
