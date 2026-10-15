package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for POST /facturas/generar.
 */
public record GenerarFacturaRequest(
        @NotBlank(message = "El periodo de facturación es obligatorio")
        String periodoCodigo,

        BillingType tipoFacturacion,

        BillingCutType tipoCorte,

        String nit
) {
    public BillingType resolvedTipoFacturacion() {
        return tipoFacturacion != null ? tipoFacturacion : BillingType.F1;
    }

    public BillingCutType resolvedTipoCorte() {
        return tipoCorte != null ? tipoCorte : BillingCutType.ADVANCE;
    }
}
