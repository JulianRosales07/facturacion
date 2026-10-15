package com.sistemaspalacios.billing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for POST /facturas/{id}/anular (HU-19).
 */
public record AnularFacturaRequest(
        @NotBlank(message = "El motivo de la anulación es obligatorio")
        String motivo
) {}
