package com.sistemaspalacios.billing.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Item specification for custom/partial credit note requests.
 */
public record ItemNotaCreditoRequest(
        UUID itemFacturableId,
        String descripcion,
        BigDecimal cantidad,
        BigDecimal valorUnitario,
        BigDecimal valorDescuento,
        BigDecimal porcentajeIva
) {}
