package com.sistemaspalacios.billing.domain.model;

/** Estado de una retención seleccionada dentro de un pago. */
public enum SelectionStatus {
    /** Seleccionada y liquidada; el pago aún no se confirma. */
    SELECTED,
    /** Seleccionada pero no aplica (p. ej. no alcanza la base mínima): valor retenido 0. */
    NOT_APPLICABLE,
    /** El pago se confirmó y la retención quedó aplicada. Inmutable. */
    APPLIED,
    /** El pago se anuló o fue rechazado antes de confirmarse. Inmutable. */
    CANCELLED
}
