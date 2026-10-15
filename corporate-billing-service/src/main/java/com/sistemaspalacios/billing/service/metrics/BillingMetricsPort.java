package com.sistemaspalacios.billing.service.metrics;

/**
 * Port for recording corporate billing business metrics and telemetry.
 * Decouples domain/service layers from specific telemetry frameworks like Micrometer.
 */
public interface BillingMetricsPort {

    void recordInvoiceGenerated(String billingType, boolean success);

    void recordBatchExported(String billingType, int invoiceCount);

    void recordBatchConfirmed(String billingType, int invoiceCount);

    void recordPdfClassified(String matchStrategy, boolean matched);

    void recordOrdersSynchronized(String periodCode, int siteCount);
}
