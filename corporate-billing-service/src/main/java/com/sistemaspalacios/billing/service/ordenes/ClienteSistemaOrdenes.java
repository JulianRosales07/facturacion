package com.sistemaspalacios.billing.service.ordenes;

import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;

import java.util.List;

/**
 * Interfaz en español para el cliente del Sistema de Órdenes.
 * Especificada en el prompt de arquitectura e integración de Sprint 3.
 */
public interface ClienteSistemaOrdenes extends OrderSystemClient {

    default List<SubscriptionOrderDto> obtenerSuscripciones(String periodo) {
        return fetchSubscriptions(periodo);
    }

    default List<OrderUpdateDto> obtenerNovedades(String periodo) {
        return fetchUpdates(periodo);
    }
}
