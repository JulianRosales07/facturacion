package com.sistemaspalacios.billing.service.ordenes;

/**
 * Exception thrown when communication with the Order System fails.
 */
public class OrderSystemException extends RuntimeException {

    public OrderSystemException(String message) {
        super(message);
    }

    public OrderSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
