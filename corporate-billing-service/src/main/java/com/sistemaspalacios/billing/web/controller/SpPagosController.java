package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.SpPagosQueryService;
import com.sistemaspalacios.billing.service.dto.FacturaSpPagosDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST diseñado para el sistema de recaudos y pasarela de pagos SP Pagos.
 * Entrega las facturas estructuradas en JSON con balance liquidable, desglose de ítems,
 * impuestos, retenciones y enlaces al documento oficial.
 */
@RestController
@RequestMapping({"/api/v1/pasarela/facturas", "/pasarela/facturas"})
@PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
public class SpPagosController {

    private final SpPagosQueryService spPagosQueryService;

    public SpPagosController(SpPagosQueryService spPagosQueryService) {
        this.spPagosQueryService = Objects.requireNonNull(spPagosQueryService, "spPagosQueryService cannot be null");
    }

    /**
     * Obtiene el JSON estructurado de una factura para liquidación y pago en SP Pagos.
     *
     * @param id Identificador UUID de la factura.
     * @return FacturaSpPagosDto con todos los datos de liquidación.
     */
    @GetMapping("/{id}")
    public ResponseEntity<FacturaSpPagosDto> getInvoiceForPayment(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(spPagosQueryService.getInvoiceForPayment(id));
    }

    /**
     * Consulta facturas de un cliente para visualización o cobro en SP Pagos.
     *
     * @param nit            NIT del cliente (requerido para rol pasarela).
     * @param numeroFactura  Número o consecutivo opcional de la factura.
     * @param soloPendientes Si es true (por defecto), solo retorna facturas con saldo pendiente > 0.
     * @return Lista de facturas en formato JSON para SP Pagos.
     */
    @GetMapping
    public ResponseEntity<List<FacturaSpPagosDto>> listInvoicesForPayment(
            @RequestParam(name = "nit", required = false) String nit,
            @RequestParam(name = "numeroFactura", required = false) String numeroFactura,
            @RequestParam(name = "soloPendientes", required = false, defaultValue = "true") boolean soloPendientes
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isOnlyPasarela = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROL_PASARELA") || a.getAuthority().equals("ROLE_PASARELA"))
                && auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().contains("ANALISTA") || a.getAuthority().contains("ADMINISTRADOR"));

        if (isOnlyPasarela && (nit == null || nit.isBlank())) {
            throw new AccessDeniedException("El rol ROL_PASARELA requiere especificar el NIT para consultar facturas en la pasarela");
        }

        return ResponseEntity.ok(spPagosQueryService.listInvoicesForPayment(nit, numeroFactura, soloPendientes));
    }

    /**
     * Obtiene la factura vigente de un cliente en un periodo contable específico.
     *
     * @param nit     NIT del cliente.
     * @param periodo Periodo contable (ej. "2026-09").
     * @return Factura vigente lista para pago en SP Pagos.
     */
    @GetMapping("/cliente/{nit}/vigente")
    public ResponseEntity<FacturaSpPagosDto> getCurrentInvoiceForClientPeriod(
            @PathVariable("nit") String nit,
            @RequestParam(name = "periodo") String periodo
    ) {
        return ResponseEntity.ok(spPagosQueryService.getCurrentInvoiceForClientPeriod(nit, periodo));
    }
}
