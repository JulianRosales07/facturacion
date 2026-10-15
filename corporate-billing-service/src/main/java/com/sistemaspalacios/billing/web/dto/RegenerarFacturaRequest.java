package com.sistemaspalacios.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload para POST /facturas/{id}/regenerar (Sprint 10).
 * <p>
 * La regeneración crea una versión nueva de la factura acumulando las novedades del
 * periodo y deja la anterior como histórico en estado REEMPLAZADA.
 */
public record RegenerarFacturaRequest(

        @NotBlank(message = "El motivo de la regeneración es obligatorio")
        @Size(max = 255, message = "El motivo no puede exceder 255 caracteres")
        String motivo,

        /**
         * Autoriza regenerar una factura que ya tiene CUFE, es decir que ya fue transmitida
         * a la DIAN. Por defecto es {@code false} y la operación se rechaza, porque alterar
         * un documento electrónico ya radicado exige una decisión consciente del operador.
         */
        Boolean confirmarCufeTransmitido
) {
    public boolean resolvedConfirmarCufeTransmitido() {
        return Boolean.TRUE.equals(confirmarCufeTransmitido);
    }
}
