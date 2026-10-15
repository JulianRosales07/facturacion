package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.service.ClientRetentionConfigurationService;
import com.sistemaspalacios.billing.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Configuración de retenciones autorizada por cada cliente para precargar sus pagos futuros.
 */
@RestController
@RequestMapping({"/api/v1/clientes/{nit}/configuracion-retenciones", "/clientes/{nit}/configuracion-retenciones"})
@PreAuthorize("hasAnyAuthority('PAYMENTS_VIEW', 'PAYMENTS_REGISTER', 'ROL_PASARELA', 'ROLE_PASARELA')")
public class ClientRetentionConfigurationController {

    private final ClientRetentionConfigurationService service;

    public ClientRetentionConfigurationController(ClientRetentionConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ClientRetentionConfigurationService.ClientRetentionConfigurationView> get(@PathVariable String nit) {
        return service.find(nit).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Guarda la configuración solo si {@code autorizado} es true (consentimiento expreso del cliente). */
    @PutMapping
    @PreAuthorize("hasAnyAuthority('PAYMENTS_REGISTER', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<ClientRetentionConfigurationService.ClientRetentionConfigurationView> save(
            @PathVariable String nit, @RequestBody SaveConfigurationRequest request) {
        return ResponseEntity.ok(service.save(nit, Boolean.TRUE.equals(request.autorizado()), request.applicationMode(),
                request.selectedConcepts()));
    }

    @DeleteMapping
    @PreAuthorize("hasAnyAuthority('PAYMENTS_REGISTER', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<Void> delete(@PathVariable String nit) {
        service.delete(nit);
        return ResponseEntity.noContent().build();
    }

    public record SaveConfigurationRequest(
            Boolean autorizado,
            RetentionApplicationMode applicationMode,
            List<PaymentService.ConceptSelection> selectedConcepts
    ) {}
}
