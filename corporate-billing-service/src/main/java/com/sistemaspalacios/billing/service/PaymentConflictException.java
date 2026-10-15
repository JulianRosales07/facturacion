package com.sistemaspalacios.billing.service;

public class PaymentConflictException extends RuntimeException {
    public PaymentConflictException(String message) { super(message); }
}
