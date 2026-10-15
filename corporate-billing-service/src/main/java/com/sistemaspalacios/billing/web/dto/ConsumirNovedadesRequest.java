package com.sistemaspalacios.billing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Petición para consumir y procesar novedades de órdenes de un cliente para un periodo específico.
 */
public record ConsumirNovedadesRequest(
        @NotBlank(message = "El NIT del cliente es obligatorio")
        String nit,

        String periodo
) {
}
