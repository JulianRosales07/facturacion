package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioLote;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.siigo.OfficialInvoiceEmitter;
import com.sistemaspalacios.billing.service.siigo.SiigoConfirmationParser;
import com.sistemaspalacios.billing.service.siigo.SiigoConfirmationRecord;
import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for managing export batches to Siigo (HU-21, HU-22, HU-23, HU-24).
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final RepositorioLote repositorioLote;
    private final RepositorioFactura repositorioFactura;
    private final RepositorioPeriodo repositorioPeriodo;
    private final OfficialInvoiceEmitter officialInvoiceEmitter;
    private final SiigoSharedFolderReader siigoSharedFolderReader;
    private final SiigoConfirmationParser siigoConfirmationParser;
    private final com.sistemaspalacios.billing.service.metrics.BillingMetricsPort billingMetrics;
    private final com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public BatchService(
            RepositorioLote repositorioLote,
            RepositorioFactura repositorioFactura,
            RepositorioPeriodo repositorioPeriodo,
            OfficialInvoiceEmitter officialInvoiceEmitter,
            SiigoSharedFolderReader siigoSharedFolderReader,
            SiigoConfirmationParser siigoConfirmationParser,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.sistemaspalacios.billing.service.metrics.BillingMetricsPort billingMetrics,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider
    ) {
        this.repositorioLote = repositorioLote;
        this.repositorioFactura = repositorioFactura;
        this.repositorioPeriodo = repositorioPeriodo;
        this.officialInvoiceEmitter = officialInvoiceEmitter;
        this.siigoSharedFolderReader = siigoSharedFolderReader;
        this.siigoConfirmationParser = siigoConfirmationParser;
        this.billingMetrics = billingMetrics;
        this.currentUserProvider = currentUserProvider;
    }

    public BatchService(
            RepositorioLote repositorioLote,
            RepositorioFactura repositorioFactura,
            RepositorioPeriodo repositorioPeriodo,
            OfficialInvoiceEmitter officialInvoiceEmitter,
            SiigoSharedFolderReader siigoSharedFolderReader,
            SiigoConfirmationParser siigoConfirmationParser
    ) {
        this(repositorioLote, repositorioFactura, repositorioPeriodo, officialInvoiceEmitter, siigoSharedFolderReader, siigoConfirmationParser, null, null);
    }

    /**
     * Generates an export batch for the given period and billing type.
     */
    @Transactional
    public ExportBatch generateBatch(String periodCode, BillingType billingType, BillingCutType cutType) {
        BillingPeriod period = repositorioPeriodo.findByCode(periodCode)
                .orElseThrow(() -> new IllegalArgumentException("Billing period not found with code: " + periodCode));

        List<Invoice> candidateInvoices = repositorioFactura.findByPeriodIdAndBillingType(period.getId(), billingType)
                .stream()
                .filter(i -> i.getStatus() != InvoiceStatus.VOIDED && i.getStatus() != InvoiceStatus.DRAFT)
                .toList();

        if (candidateInvoices.isEmpty()) {
            throw new IllegalStateException("No active consolidated invoices found for period " + periodCode + " and type " + billingType);
        }

        String fileName = String.format("LOTE_%s_%s.txt", billingType.name(), periodCode);
        String cutTypeName = cutType != null ? cutType.name() : BillingCutType.ADVANCE.name();
        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        ExportBatch batch = new ExportBatch(
                UUID.randomUUID(),
                period.getId(),
                billingType,
                cutTypeName,
                fileName,
                candidateInvoices.size(),
                ExportBatchStatus.GENERADO
        );
        batch.setGeneratedBy(currentUser);

        ExportBatch savedBatch = repositorioLote.save(batch);

        for (Invoice invoice : candidateInvoices) {
            invoice.setExportBatchId(savedBatch.getId());
            repositorioFactura.save(invoice);
        }

        log.info("Created ExportBatch {} for period {} with {} invoices by user '{}'",
                savedBatch.getId(), periodCode, candidateInvoices.size(), currentUser);
        if (billingMetrics != null) {
            billingMetrics.recordBatchExported(savedBatch.getBillingType().name(), candidateInvoices.size());
        }
        return savedBatch;
    }

    /**
     * Generates the raw bytes of the Siigo flat file for downloading.
     */
    @Transactional(readOnly = true)
    public byte[] getBatchFile(UUID batchId) {
        ExportBatch batch = repositorioLote.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        List<Invoice> invoices = repositorioFactura.findByExportBatchId(batchId);
        if (invoices.isEmpty()) {
            // Fallback: invoices of the batch's period and type
            invoices = repositorioFactura.findByPeriodIdAndBillingType(batch.getPeriodId(), batch.getBillingType())
                    .stream()
                    .filter(i -> i.getStatus() != InvoiceStatus.VOIDED)
                    .toList();
        }

        return officialInvoiceEmitter.exportBatch(batch, invoices);
    }

    /**
     * Confirms the batch using explicit confirmation records (HU-23).
     */
    @Transactional
    public ExportBatch confirmBatch(UUID batchId, List<SiigoConfirmationRecord> confirmations) {
        ExportBatch batch = repositorioLote.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        List<Invoice> invoices = repositorioFactura.findByExportBatchId(batchId);
        if (invoices.isEmpty()) {
            invoices = repositorioFactura.findByPeriodIdAndBillingType(batch.getPeriodId(), batch.getBillingType());
        }

        int updatedCount = 0;
        for (SiigoConfirmationRecord conf : confirmations) {
            Optional<Invoice> matched = findMatchingInvoice(invoices, conf);
            if (matched.isPresent()) {
                Invoice inv = matched.get();
                if (conf.documentNumber() != null && !conf.documentNumber().isBlank()) {
                    inv.setInvoiceNumber(conf.documentNumber());
                }
                if (conf.cufe() != null && !conf.cufe().isBlank()) {
                    inv.setCufe(conf.cufe());
                }
                if (conf.deliveryStatus() != null && !conf.deliveryStatus().isBlank()) {
                    inv.setDispatchStatus(conf.deliveryStatus());
                }
                repositorioFactura.save(inv);
                updatedCount++;
            }
        }

        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        batch.confirm(currentUser);
        ExportBatch confirmedBatch = repositorioLote.save(batch);
        log.info("Batch {} confirmed by user '{}'. Updated {} invoices out of {} records",
                batchId, currentUser, updatedCount, confirmations.size());
        if (billingMetrics != null) {
            billingMetrics.recordBatchConfirmed(batch.getBillingType().name(), updatedCount);
        }
        return confirmedBatch;
    }

    /**
     * Scans the shared folder on the server for confirmation flat files and confirms the batch.
     */
    @Transactional
    public ExportBatch confirmBatchFromSharedFolder(UUID batchId) {
        List<File> flatFiles = siigoSharedFolderReader.listConfirmationFlatFiles();
        if (flatFiles.isEmpty()) {
            log.warn("No confirmation flat files found in Siigo shared folder: {}", siigoSharedFolderReader.getSharedFolderPath());
            throw new IllegalStateException("No confirmation flat files found in shared folder: " + siigoSharedFolderReader.getSharedFolderPath());
        }

        List<SiigoConfirmationRecord> allRecords = new ArrayList<>();
        for (File file : flatFiles) {
            try {
                String content = Files.readString(file.toPath());
                List<SiigoConfirmationRecord> parsed = siigoConfirmationParser.parse(content);
                allRecords.addAll(parsed);
                log.info("Parsed {} confirmation records from shared file {}", parsed.size(), file.getName());
            } catch (Exception e) {
                log.error("Failed to read confirmation file {}: {}", file.getName(), e.getMessage());
            }
        }

        if (allRecords.isEmpty()) {
            throw new IllegalStateException("Confirmation files were found but contained no valid records");
        }

        return confirmBatch(batchId, allRecords);
    }

    private Optional<Invoice> findMatchingInvoice(List<Invoice> invoices, SiigoConfirmationRecord conf) {
        // 1. Match by exact invoice number if set
        if (conf.documentNumber() != null && !conf.documentNumber().isBlank()) {
            Optional<Invoice> byNum = invoices.stream()
                    .filter(i -> conf.documentNumber().equalsIgnoreCase(i.getInvoiceNumber()))
                    .findFirst();
            if (byNum.isPresent()) return byNum;
        }

        // 2. Match by NIT
        if (conf.clientNit() != null && !conf.clientNit().isBlank()) {
            String cleanConfNit = conf.clientNit().replaceAll("-.*$", "").trim();
            Optional<Invoice> byNit = invoices.stream()
                    .filter(i -> {
                        String cleanInvNit = i.getClientNit() != null ? i.getClientNit().replaceAll("-.*$", "").trim() : "";
                        return cleanConfNit.equalsIgnoreCase(cleanInvNit);
                    })
                    .findFirst();
            if (byNit.isPresent()) return byNit;
        }

        // 3. Match by CUFE if present
        if (conf.cufe() != null && !conf.cufe().isBlank()) {
            Optional<Invoice> byCufe = invoices.stream()
                    .filter(i -> conf.cufe().equalsIgnoreCase(i.getCufe()))
                    .findFirst();
            if (byCufe.isPresent()) return byCufe;
        }

        return Optional.empty();
    }

    public Optional<ExportBatch> getBatchById(UUID batchId) {
        return repositorioLote.findById(batchId);
    }

    public List<ExportBatch> getBatchesByPeriod(UUID periodId) {
        return repositorioLote.findByPeriodId(periodId);
    }

    public List<ExportBatch> getAllBatches() {
        return repositorioLote.findAll();
    }
}
