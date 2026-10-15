package com.sistemaspalacios.billing.service;

public class InvoicePdfStorageException extends RuntimeException {
    public InvoicePdfStorageException(String message) {
        super(message);
    }

    public InvoicePdfStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
