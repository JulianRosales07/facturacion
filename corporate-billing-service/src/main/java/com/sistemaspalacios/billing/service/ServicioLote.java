package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ExportBatch;

import java.util.UUID;

/**
 * Interfaz en español para el servicio de aplicación de lotes de exportación (regla DDD-Lite).
 */
public interface ServicioLote {

    ExportBatch generar(String periodoCodigo, BillingType tipoFacturacion, BillingCutType tipoCorte);

    byte[] obtenerArchivo(UUID loteId);

    ExportBatch confirmar(UUID loteId);
}
