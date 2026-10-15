package com.sistemaspalacios.billing.web.dto;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GenerarLoteRequest(
        @NotBlank(message = "El periodo es obligatorio (ej. 2026-08)")
        String periodo,

        @NotNull(message = "El tipo de facturación es obligatorio (F1 o F20)")
        BillingType tipoFacturacion,

        BillingCutType tipoCorte
) {
    public GenerarLoteRequest {
        if (tipoCorte == null) {
            tipoCorte = BillingCutType.ADVANCE;
        }
    }
}
