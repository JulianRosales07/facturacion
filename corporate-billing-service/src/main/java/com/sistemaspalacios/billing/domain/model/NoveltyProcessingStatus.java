package com.sistemaspalacios.billing.domain.model;

/**
 * Estado de una novedad frente a la facturación (novedad_orden.estado_procesamiento).
 */
public enum NoveltyProcessingStatus {
    /** Registrada; entra cuando se genere o recalcule la factura de su periodo. */
    PENDIENTE,
    /** Incorporada a la factura en preparación de su periodo. */
    APLICADA,
    /**
     * Su periodo ya estaba emitido o pagado. La factura no se toca: el impacto se cobra o
     * compensa en la siguiente factura en preparación del cliente.
     */
    PENDIENTE_AJUSTE,
    /** El ajuste ya entró en otra factura del cliente. */
    AJUSTE_APLICADO
}
