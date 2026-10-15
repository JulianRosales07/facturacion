package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncResultDto;

/**
 * Interfaz en español para el servicio de aplicación de Órdenes.
 * Especificada en PROMPT_corporate-billing-service.md.
 */
public interface ServicioOrdenes extends OrderService {

    default OrderSyncResultDto sincronizar(String periodo) {
        return syncPeriod(periodo);
    }
}
