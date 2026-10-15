package com.sistemaspalacios.billing.infrastructure.realtime;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Aviso enviado por WebSocket. No es la fuente de verdad: indica que algo cambió para que el
 * frontend vuelva a consultar el estado real por REST. {@code eventId} permite descartar duplicados.
 */
public record RealtimeEvent(
        String eventId,
        String eventType,
        String occurredAt,
        Map<String, Object> payload
) {
    public static final String INVOICE_STATUS_CHANGED = "INVOICE_STATUS_CHANGED";
    public static final String INVOICE_PDF_AVAILABLE = "INVOICE_PDF_AVAILABLE";
    public static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    public static RealtimeEvent of(String eventType, Map<String, Object> payload) {
        return new RealtimeEvent(UUID.randomUUID().toString(), eventType,
                OffsetDateTime.now(BOGOTA).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), payload);
    }
}
