package com.sistemaspalacios.billing.domain.service;

public class UnbalancedAccountingEntryException extends RuntimeException {
    public UnbalancedAccountingEntryException(String message) {
        super(message);
    }
}
