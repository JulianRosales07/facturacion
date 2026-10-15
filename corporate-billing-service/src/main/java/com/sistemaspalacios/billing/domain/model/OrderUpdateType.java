package com.sistemaspalacios.billing.domain.model;

public enum OrderUpdateType {
    ALTA(1),
    BAJA(2),
    CAMBIO_PLAN(3),
    CAMBIO_SERVICIO(4),
    SUSPENSION(5),
    REACTIVACION(6),
    TRASLADO_DIRECCION(7);

    private final int id;

    OrderUpdateType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
