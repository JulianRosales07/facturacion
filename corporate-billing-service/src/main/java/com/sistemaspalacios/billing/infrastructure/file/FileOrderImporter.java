package com.sistemaspalacios.billing.infrastructure.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.ImportedFile;
import com.sistemaspalacios.billing.domain.model.ImportedFileLine;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncRecordDto;
import com.sistemaspalacios.billing.repository.RepositorioArchivoImportado;
import com.sistemaspalacios.billing.repository.RepositorioArchivoImportadoLinea;
import com.sistemaspalacios.billing.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;

/**
 * Alternative order data importer from files (infrastructure/file).
 * Fulfills HU-03, HU-05, HU-27:
 * - Line-by-line traceability in archivo_importado / archivo_importado_linea.
 * - Stores raw payload in JSONB.
 * - Non-blocking error handling: failed lines do not prevent valid lines from being committed.
 */
@Component
public class FileOrderImporter {

    private static final Logger log = LoggerFactory.getLogger(FileOrderImporter.class);

    private final OrderService orderService;
    private final RepositorioArchivoImportado repositorioArchivoImportado;
    private final RepositorioArchivoImportadoLinea repositorioArchivoImportadoLinea;
    private final ObjectMapper objectMapper;

    public FileOrderImporter(
            OrderService orderService,
            RepositorioArchivoImportado repositorioArchivoImportado,
            RepositorioArchivoImportadoLinea repositorioArchivoImportadoLinea,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.orderService = orderService;
        this.repositorioArchivoImportado = repositorioArchivoImportado;
        this.repositorioArchivoImportadoLinea = repositorioArchivoImportadoLinea;
        this.objectMapper = objectMapper != null
                ? objectMapper
                : new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    /**
     * Imports orders from a multi-line JSON or NDJSON content string.
     * Each line contains an OrderSyncRecordDto in JSON format.
     */
    @Transactional
    public ImportedFile importFromLines(String fileName, OrderOrigin origin, String periodCode, List<String> rawLines) {
        Objects.requireNonNull(fileName, "fileName cannot be null");
        Objects.requireNonNull(origin, "origin cannot be null");
        Objects.requireNonNull(periodCode, "periodCode cannot be null");
        Objects.requireNonNull(rawLines, "rawLines cannot be null");

        log.info("Starting file import '{}' (origin: {}) for period '{}' with {} lines",
                fileName, origin, periodCode, rawLines.size());

        ImportedFile importedFile = new ImportedFile(null, fileName, origin);
        importedFile.markProcessing();
        importedFile = repositorioArchivoImportado.save(importedFile);

        int totalCount = rawLines.size();
        int errorCount = 0;
        int lineNumber = 1;

        for (String rawLine : rawLines) {
            String trimmed = rawLine != null ? rawLine.trim() : "";
            if (trimmed.isEmpty()) {
                lineNumber++;
                continue;
            }

            ImportedFileLine lineEntity = new ImportedFileLine(
                    null,
                    importedFile.getId(),
                    lineNumber,
                    trimmed
            );

            try {
                OrderSyncRecordDto record = objectMapper.readValue(trimmed, OrderSyncRecordDto.class);
                if (record.nit() == null || record.externalOrderCode() == null) {
                    throw new IllegalArgumentException("Missing required fields: nit and externalOrderCode are mandatory");
                }

                SubscriptionSite site = orderService.processRecord(record, periodCode);
                lineEntity.markSuccess(site.getId(), null);
                log.debug("Line {} processed successfully for site {}", lineNumber, site.getExternalOrderCode());
            } catch (Exception e) {
                errorCount++;
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                lineEntity.markError(errorMsg);
                log.warn("Line {} failed with error: {} (processing continues)", lineNumber, errorMsg);
            }

            repositorioArchivoImportadoLinea.save(lineEntity);
            lineNumber++;
        }

        importedFile.complete(totalCount, errorCount);
        importedFile = repositorioArchivoImportado.save(importedFile);

        log.info("Completed file import '{}': {} total, {} errors", fileName, totalCount, errorCount);
        return importedFile;
    }

    /**
     * Imports orders from a string payload where lines are delimited by newlines.
     */
    @Transactional
    public ImportedFile importFromStringPayload(String fileName, OrderOrigin origin, String periodCode, String payload) {
        List<String> lines = new BufferedReader(new StringReader(payload))
                .lines()
                .filter(l -> !l.isBlank())
                .toList();

        return importFromLines(fileName, origin, periodCode, lines);
    }
}
