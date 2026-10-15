package com.sistemaspalacios.billing.service.ordenes;

import com.sistemaspalacios.billing.service.ordenes.dto.OrderUpdateDto;
import com.sistemaspalacios.billing.service.ordenes.dto.SubscriptionOrderDto;

import java.util.List;

/**
 * Interface representing the integration boundary with the external Order Management System.
 * Outbound port residing in the application service layer.
 */
public interface OrderSystemClient {

    /**
     * Retrieves active corporate subscriptions/sites for the specified billing period.
     *
     * @param periodCode Period identifier formatted as YYYY-MM
     * @return List of subscription records
     */
    List<SubscriptionOrderDto> fetchSubscriptions(String periodCode);

    /**
     * Retrieves operational novelties/updates for the specified billing period.
     *
     * @param periodCode Period identifier formatted as YYYY-MM
     * @return List of order updates
     */
    List<OrderUpdateDto> fetchUpdates(String periodCode);
}
