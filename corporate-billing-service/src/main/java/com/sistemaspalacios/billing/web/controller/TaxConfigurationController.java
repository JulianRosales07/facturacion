package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.TaxConcept;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.TaxConfigurationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for general versioned Tax Configuration (Sprint 3, Sprint 8 HU-32, Sprint 9 HU-50).
 * All per-client configuration has been eliminated in favor of a general, dated, versioned catalog.
 * Lectura: TAX_CONCEPTS_VIEW (y la pasarela para el catálogo activo).
 * Modificaciones: TAX_CONCEPTS_MANAGE.
 */
@RestController
@RequestMapping({"/api/v1/impuestos", "/impuestos"})
public class TaxConfigurationController {

    private final TaxConfigurationService taxConfigurationService;

    public TaxConfigurationController(TaxConfigurationService taxConfigurationService) {
        this.taxConfigurationService = Objects.requireNonNull(taxConfigurationService, "taxConfigurationService cannot be null");
    }

    /**
     * Catálogo general seleccionable al pagar (HU-51): solo vigencias activas a la fecha actual.
     * El {@code id} es el de la vigencia; es lo único que el cliente devuelve al simular o pagar,
     * y el servidor vuelve a leer porcentaje, base mínima, tipo y versión desde la base de datos.
     */
    @GetMapping("/conceptos")
    @PreAuthorize("hasAnyAuthority('TAX_CONCEPTS_VIEW', 'PAYMENTS_REGISTER', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<List<CatalogConceptResponse>> listCatalogConcepts() {
        List<CatalogConceptResponse> catalog = taxConfigurationService.findActiveValiditiesAtDate(LocalDate.now()).stream()
                .sorted(java.util.Comparator.comparing((TaxConceptValidity v) -> v.getConceptCode().getId())
                        .thenComparing(TaxConceptValidity::getName))
                .map(CatalogConceptResponse::from)
                .toList();
        return ResponseEntity.ok(catalog);
    }

    /** Tipos de concepto tributario (RETEFUENTE, RETEICA, RETEIVA, ESTAMPILLA). */
    @GetMapping("/conceptos/tipos")
    @PreAuthorize("hasAuthority('TAX_CONCEPTS_VIEW')")
    public ResponseEntity<List<TaxConcept>> listConcepts() {
        return ResponseEntity.ok(taxConfigurationService.listConcepts());
    }

    public record CatalogConceptResponse(
            UUID id,
            String nombre,
            TaxConceptCode tipo,
            BigDecimal porcentajeMaximo,
            BigDecimal baseMinima,
            int version,
            LocalDate vigenteDesde,
            LocalDate vigenteHasta,
            String municipio
    ) {
        static CatalogConceptResponse from(TaxConceptValidity v) {
            return new CatalogConceptResponse(v.getId(), v.getName(), v.getConceptCode(), v.getMaxPercentage(),
                    v.getMinimumBase().amount(), v.getVersionNumber(), v.getValidFrom(), v.getValidTo(), v.getMunicipality());
        }
    }

    @GetMapping("/vigencias")
    @PreAuthorize("hasAuthority('TAX_CONCEPTS_VIEW')")
    public ResponseEntity<List<TaxConceptValidity>> listValidities(
            @RequestParam(name = "fecha", required = false) LocalDate date
    ) {
        if (date != null) {
            return ResponseEntity.ok(taxConfigurationService.findActiveValiditiesAtDate(date));
        }
        return ResponseEntity.ok(taxConfigurationService.listAllValidities());
    }

    @PostMapping("/vigencias")
    @PreAuthorize("hasAuthority('TAX_CONCEPTS_MANAGE')")
    public ResponseEntity<TaxConceptValidity> createValidity(@RequestBody TaxConceptValidityRequest request) {
        TaxConceptValidity validity = new TaxConceptValidity(
                UUID.randomUUID(),
                request.conceptCode(),
                request.nombre(),
                request.numeroVersion() != null ? request.numeroVersion() : 1,
                request.classification(),
                request.municipality(),
                Money.of(request.minimumBase() != null ? request.minimumBase() : BigDecimal.ZERO),
                request.percentage() != null ? request.percentage() : BigDecimal.ZERO,
                request.validFrom() != null ? request.validFrom() : LocalDate.now(),
                request.validTo(),
                request.active() == null || request.active()
        );
        TaxConceptValidity created = taxConfigurationService.createValidity(validity);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/vigencias/{id}/nueva-version")
    @PreAuthorize("hasAuthority('TAX_CONCEPTS_MANAGE')")
    public ResponseEntity<TaxConceptValidity> createNewVersion(
            @PathVariable UUID id,
            @RequestBody CreateNewVersionRequest request
    ) {
        TaxConceptValidity newVersion = taxConfigurationService.createNewVersion(
                id,
                request.newMinimumBase() != null ? Money.of(request.newMinimumBase()) : null,
                request.newPercentage(),
                request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(newVersion);
    }

    @PutMapping("/vigencias/{id}")
    @PreAuthorize("hasAuthority('TAX_CONCEPTS_MANAGE')")
    public ResponseEntity<TaxConceptValidity> updateValidity(
            @PathVariable UUID id,
            @RequestBody TaxConceptValidityRequest request
    ) {
        TaxConceptValidity updated = new TaxConceptValidity(
                id,
                request.conceptCode(),
                request.nombre(),
                request.numeroVersion() != null ? request.numeroVersion() : 1,
                request.classification(),
                request.municipality(),
                Money.of(request.minimumBase() != null ? request.minimumBase() : BigDecimal.ZERO),
                request.percentage() != null ? request.percentage() : BigDecimal.ZERO,
                request.validFrom() != null ? request.validFrom() : LocalDate.now(),
                request.validTo(),
                request.active() == null || request.active()
        );
        TaxConceptValidity result = taxConfigurationService.updateValidity(id, updated);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/vigencias/{id}")
    @PreAuthorize("hasAuthority('TAX_CONCEPTS_MANAGE')")
    public ResponseEntity<Void> deactivateValidity(@PathVariable UUID id) {
        taxConfigurationService.deactivateValidity(id);
        return ResponseEntity.noContent().build();
    }

    public record TaxConceptValidityRequest(
            TaxConceptCode conceptCode,
            String nombre,
            Integer numeroVersion,
            ServiceClassification classification,
            String municipality,
            BigDecimal minimumBase,
            // Porcentaje MÁXIMO que el cliente puede practicar; la tarifa la ingresa el cliente al pagar.
            @com.fasterxml.jackson.annotation.JsonAlias("porcentajeMaximo") BigDecimal percentage,
            LocalDate validFrom,
            LocalDate validTo,
            Boolean active
    ) {
        public TaxConceptValidityRequest(
                TaxConceptCode conceptCode,
                ServiceClassification classification,
                String municipality,
                BigDecimal minimumBase,
                BigDecimal percentage,
                LocalDate validFrom,
                LocalDate validTo,
                Boolean active
        ) {
            this(conceptCode, null, 1, classification, municipality, minimumBase, percentage, validFrom, validTo, active);
        }
    }

    public record CreateNewVersionRequest(
            BigDecimal newMinimumBase,
            BigDecimal newPercentage,
            LocalDate effectiveFrom
    ) {}
}
