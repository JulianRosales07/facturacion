package com.sistemaspalacios.billing.domain.model;

import java.util.Locale;
import java.util.Set;

/**
 * Resultado de una transacción según la pasarela, mapeado explícitamente desde el valor que
 * envía cada proveedor. Un valor desconocido no se asume aprobado: se rechaza la notificación
 * para que quede registrada y el proveedor la reintente.
 */
public enum ProviderPaymentStatus {
    APPROVED, REJECTED, PENDING;

    private static final Set<String> APPROVED_VALUES = Set.of(
            "APROBADA", "APROBADO", "APPROVED", "APPROVED_TRANSACTION", "SUCCESS", "SUCCESSFUL", "COMPLETED", "OK", "00");
    private static final Set<String> REJECTED_VALUES = Set.of(
            "RECHAZADA", "RECHAZADO", "DECLINED", "REJECTED", "FAILED", "FALLIDA", "ERROR", "EXPIRED", "VOIDED", "CANCELLED");
    private static final Set<String> PENDING_VALUES = Set.of(
            "PENDIENTE", "PENDING", "EN_PROCESO", "IN_PROCESS", "PROCESSING");

    public static ProviderPaymentStatus fromProvider(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La confirmación debe indicar el estado de la transacción");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (APPROVED_VALUES.contains(normalized)) return APPROVED;
        if (REJECTED_VALUES.contains(normalized)) return REJECTED;
        if (PENDING_VALUES.contains(normalized)) return PENDING;
        throw new IllegalArgumentException("Estado de transacción no reconocido: " + value);
    }
}
