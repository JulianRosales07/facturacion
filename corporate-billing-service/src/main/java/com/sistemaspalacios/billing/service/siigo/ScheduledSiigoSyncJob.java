package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;
import com.sistemaspalacios.billing.domain.model.SyncStatusEntity;
import com.sistemaspalacios.billing.repository.RepositorioEstadoSincronizacion;
import com.sistemaspalacios.billing.repository.RepositorioLote;
import com.sistemaspalacios.billing.service.BatchService;
import com.sistemaspalacios.billing.service.CarteraSyncService;
import com.sistemaspalacios.billing.service.SiigoPdfImporterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hourly scheduled background job to synchronize with Siigo shared folder (Sprint 9, HU-56).
 * Non-reentrant, fault-tolerant, and audits execution metrics into estado_sincronizacion.
 */
@Service
public class ScheduledSiigoSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSiigoSyncJob.class);

    private final BatchService batchService;
    private final SiigoPdfImporterService siigoPdfImporterService;
    private final RepositorioLote repositorioLote;
    private final RepositorioEstadoSincronizacion repositorioEstadoSincronizacion;
    /** Opcional para no romper los constructores de prueba existentes. */
    private final CarteraSyncService carteraSyncService;

    @Autowired(required = false)
    private com.sistemaspalacios.billing.service.OfficialInvoiceFolderSyncService officialInvoiceImporter;

    @org.springframework.beans.factory.annotation.Value("${siigo.sync.confirmar-lotes-salientes:false}")
    private boolean confirmOutgoingBatches = true;

    @org.springframework.beans.factory.annotation.Value("${siigo.sync.consumir-cartera:false}")
    private boolean consumeReceivables = true;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Autowired
    public ScheduledSiigoSyncJob(
            BatchService batchService,
            SiigoPdfImporterService siigoPdfImporterService,
            RepositorioLote repositorioLote,
            RepositorioEstadoSincronizacion repositorioEstadoSincronizacion,
            @Autowired(required = false) CarteraSyncService carteraSyncService
    ) {
        this.batchService = batchService;
        this.siigoPdfImporterService = siigoPdfImporterService;
        this.repositorioLote = repositorioLote;
        this.repositorioEstadoSincronizacion = repositorioEstadoSincronizacion;
        this.carteraSyncService = carteraSyncService;
    }

    public ScheduledSiigoSyncJob(
            BatchService batchService,
            SiigoPdfImporterService siigoPdfImporterService,
            RepositorioLote repositorioLote,
            RepositorioEstadoSincronizacion repositorioEstadoSincronizacion
    ) {
        this(batchService, siigoPdfImporterService, repositorioLote, repositorioEstadoSincronizacion, null);
    }

    /**
     * Executes hourly according to cron expression (default: 0 0 * * * *).
     */
    @Scheduled(cron = "${siigo.sync.cron:0 0 * * * *}")
    public SyncStatusEntity runScheduledSync() {
        return executeSync("PROGRAMADA");
    }

    /**
     * Allows manual on-demand trigger for urgent invoices without waiting for hourly cron.
     */
    public SyncStatusEntity triggerManualSync() {
        return executeSync("MANUAL");
    }

    public java.util.Optional<SyncStatusEntity> getLatestStatus() {
        return repositorioEstadoSincronizacion.findTopByOrderByStartedAtDesc();
    }

    public boolean isCurrentlyRunning() {
        return isRunning.get();
    }

    private SyncStatusEntity executeSync(String executionType) {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Previous Siigo sync cycle is still active. Skipping concurrent execution (HU-56 non-reentrant).");
            SyncStatusEntity skipped = new SyncStatusEntity(UUID.randomUUID(), executionType);
            skipped.fail("Ejecución omitida: ya existe un ciclo de sincronización en progreso.");
            return repositorioEstadoSincronizacion.save(skipped);
        }

        SyncStatusEntity status = new SyncStatusEntity(UUID.randomUUID(), executionType);
        status = repositorioEstadoSincronizacion.save(status);

        log.info("Starting Siigo hourly sync cycle [{}] ID: {}", executionType, status.getId());

        int confirmedBatchesCount = 0;
        int detectedInvoicesCount = 0;
        int classifiedPdfsCount = 0;

        // Se distinguen dos severidades:
        //  - errorMessages: fallos de un ítem concreto (un lote que no se pudo confirmar).
        //    El ciclo hizo su trabajo con el resto, así que termina CON_ADVERTENCIAS.
        //  - phaseFailure: una fase completa se cayó (p. ej. la carpeta compartida es
        //    inaccesible y no se pudo clasificar ningún PDF). Eso sí es un ciclo FALLIDA,
        //    porque no hubo forma de procesar nada de esa fase.
        List<String> errorMessages = new ArrayList<>();
        String phaseFailure = null;

        try {
            // Importar primero para que PDFs y cartera encuentren documentos recién emitidos.
            if (officialInvoiceImporter != null) {
                try {
                    var result = officialInvoiceImporter.scan();
                    detectedInvoicesCount += result.verifiedDocuments();
                    errorMessages.addAll(result.errors());
                } catch (Exception ex) {
                    phaseFailure = "Importación de facturas: " + ex.getMessage();
                    errorMessages.add(phaseFailure);
                }
            }
            // 1. Scan and confirm pending export batches from shared folder
            List<ExportBatch> pendingBatches = confirmOutgoingBatches ? repositorioLote.findByStatus(ExportBatchStatus.GENERADO) : List.of();
            for (ExportBatch batch : pendingBatches) {
                try {
                    batchService.confirmBatchFromSharedFolder(batch.getId());
                    confirmedBatchesCount++;
                    detectedInvoicesCount += batch.getTotalInvoices();
                    log.info("Successfully confirmed batch {} from shared folder", batch.getId());
                } catch (Exception e) {
                    log.warn("Batch {} could not be confirmed in this cycle: {}", batch.getId(), e.getMessage());
                    errorMessages.add("Lote " + batch.getId() + ": " + e.getMessage());
                }
            }

            // 2. Scan and classify official Siigo PDFs from shared folder
            try {
                SiigoPdfImporterService.PdfProcessingSummary pdfSummary = siigoPdfImporterService.processSharedFolderPdfs();
                classifiedPdfsCount = pdfSummary.totalMatched();
                if (pdfSummary.totalUnmatched() > 0) errorMessages.add(pdfSummary.totalUnmatched()
                        + " PDF sin asociación inequívoca: " + String.join(", ", pdfSummary.unmatched().stream().limit(10).toList()));
                log.info("PDF processing completed: {} matched, {} unmatched",
                        pdfSummary.totalMatched(), pdfSummary.totalUnmatched());
            } catch (Exception e) {
                log.error("Error processing PDFs from shared folder: {}", e.getMessage(), e);
                phaseFailure = "Clasificación PDFs: " + e.getMessage();
                errorMessages.add(phaseFailure);
            }

            // 3. Ingest the Siigo receivables snapshot (Sprint 13). El saldo de este archivo
            // es la fuente de verdad de la cartera, así que se aplica en cada ciclo.
            if (consumeReceivables && carteraSyncService != null) {
                try {
                    CarteraSyncService.CarteraSyncSummary carteraSummary =
                            carteraSyncService.processSharedFolderSnapshots();
                    log.info("Cartera sync completed: {} archivo(s) ingeridos, {} omitidos por duplicado, {} filas aplicadas",
                            carteraSummary.filesIngested(),
                            carteraSummary.filesSkippedAsDuplicate(),
                            carteraSummary.totalAppliedRows());
                } catch (Exception e) {
                    log.error("Error ingesting Siigo receivables snapshot: {}", e.getMessage(), e);
                    phaseFailure = "Corte de cartera: " + e.getMessage();
                    errorMessages.add(phaseFailure);
                }
            }

            String joinedErrors = errorMessages.isEmpty() ? null : String.join("; ", errorMessages);
            if (phaseFailure != null) {
                // El ciclo no tumba la aplicación (sigue siendo tolerante a fallos) pero queda
                // registrado como FALLIDA para que el monitoreo lo trate como incidente. Se
                // conservan los contadores de lo que sí se procesó antes del fallo.
                status.fail(confirmedBatchesCount, detectedInvoicesCount, classifiedPdfsCount, joinedErrors);
            } else {
                status.complete(confirmedBatchesCount, detectedInvoicesCount, classifiedPdfsCount, joinedErrors);
            }

        } catch (Exception fatalEx) {
            log.error("Fatal error during Siigo sync cycle: {}", fatalEx.getMessage(), fatalEx);
            status.fail("Error fatal: " + fatalEx.getMessage());
        } finally {
            isRunning.set(false);
            status = repositorioEstadoSincronizacion.save(status);
            log.info("Finished Siigo sync cycle ID {} with status {}", status.getId(), status.getStatus());
        }

        return status;
    }
}
