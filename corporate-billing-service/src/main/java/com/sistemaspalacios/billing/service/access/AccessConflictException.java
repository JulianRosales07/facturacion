package com.sistemaspalacios.billing.service.access;

/** Conflicto con el estado actual de la configuración de accesos (HTTP 409). */
public class AccessConflictException extends RuntimeException {

    public AccessConflictException(String message) {
        super(message);
    }
}
