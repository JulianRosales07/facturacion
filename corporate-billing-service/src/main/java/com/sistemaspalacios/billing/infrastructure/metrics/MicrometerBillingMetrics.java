package com.sistemaspalacios.billing.infrastructure.metrics;

import com.sistemaspalacios.billing.service.metrics.BillingMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter implementing BillingMetricsPort for Spring Boot Actuator telemetry.
 */
@Component
public class MicrometerBillingMetrics implements BillingMetricsPort {

    private final MeterRegistry registry;

    public MicrometerBillingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordInvoiceGenerated(String billingType, boolean success) {
        Counter.builder("billing.invoices.generated")
                .description("Number of corporate consolidated invoices generated")
                .tag("type", billingType != null ? billingType : "UNKNOWN")
                .tag("status", success ? "SUCCESS" : "FAILED")
                .register(registry)
                .increment();
    }

    @Override
    public void recordBatchExported(String billingType, int invoiceCount) {
        Counter.builder("billing.batches.exported")
                .description("Number of export batches sent to Siigo flat file")
                .tag("type", billingType != null ? billingType : "UNKNOWN")
                .register(registry)
                .increment();

        Counter.builder("billing.invoices.exported")
                .description("Total invoices included in export batches")
                .tag("type", billingType != null ? billingType : "UNKNOWN")
                .register(registry)
                .increment(invoiceCount);
    }

    @Override
    public void recordBatchConfirmed(String billingType, int invoiceCount) {
        Counter.builder("billing.batches.confirmed")
                .description("Number of export batches confirmed with official Siigo numbers")
                .tag("type", billingType != null ? billingType : "UNKNOWN")
                .register(registry)
                .increment();

        Counter.builder("billing.invoices.confirmed")
                .description("Total invoices confirmed with official Siigo numbers")
                .tag("type", billingType != null ? billingType : "UNKNOWN")
                .register(registry)
                .increment(invoiceCount);
    }

    @Override
    public void recordPdfClassified(String matchStrategy, boolean matched) {
        Counter.builder("billing.pdfs.classified")
                .description("Number of official PDFs processed from shared folder")
                .tag("strategy", matchStrategy != null ? matchStrategy : "UNKNOWN")
                .tag("matched", String.valueOf(matched))
                .register(registry)
                .increment();
    }

    @Override
    public void recordOrdersSynchronized(String periodCode, int siteCount) {
        Counter.builder("billing.orders.synced")
                .description("Number of sync executions from Order System")
                .tag("period", periodCode != null ? periodCode : "UNKNOWN")
                .register(registry)
                .increment();

        Counter.builder("billing.orders.synced.sites")
                .description("Total subscription sites synchronized from Order System")
                .tag("period", periodCode != null ? periodCode : "UNKNOWN")
                .register(registry)
                .increment(siteCount);
    }
}
