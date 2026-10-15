package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.OrderUpdate;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncRecordDto;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncResultDto;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;

import java.util.List;
import java.util.UUID;

/**
 * Application service for orchestrating Order System synchronization and master data updates.
 */
public interface OrderService {

    /**
     * Sincroniza suscripciones y novedades de un periodo desde el cliente de Órdenes.
     * HU-01, HU-02.
     */
    OrderSyncResultDto syncPeriod(String periodCode);

    /**
     * Sincroniza directamente con listas en memoria (útil para pruebas y adaptadores).
     */
    OrderSyncResultDto syncPeriod(String periodCode, List<SubscriptionOrderDto> subscriptions, List<OrderUpdateDto> updates);

    /**
     * Upsert idempotente de cliente corporativo (HU-02).
     */
    CorporateClient upsertClient(SubscriptionOrderDto dto);

    /**
     * Upsert idempotente de sede de suscripción y cuota de instalación si aplica (HU-02).
     */
    SubscriptionSite upsertSite(SubscriptionOrderDto dto, UUID clientId);

    /**
     * Registro/actualización idempotente de novedad de orden (HU-02, HU-04).
     */
    OrderUpdate recordUpdate(OrderUpdateDto dto, UUID periodId);

    /**
     * Procesa un registro completo compuesto (cliente, sede, cuota, novedad).
     */
    SubscriptionSite processRecord(OrderSyncRecordDto record, String periodCode);

    /**
     * Consulta de novedades de un periodo (HU-04).
     */
    List<OrderUpdate> getUpdatesByPeriod(String periodCode);

    /**
     * Consulta de novedades por sede y periodo (HU-04).
     */
    List<OrderUpdate> getUpdatesByPeriodAndSite(UUID periodId, UUID siteId);
}
