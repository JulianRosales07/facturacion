package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.*;
import com.sistemaspalacios.billing.repository.RepositorioArchivoImportado;
import com.sistemaspalacios.billing.repository.RepositorioArchivoImportadoLinea;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.service.ordenes.OrderFileParser;
import com.sistemaspalacios.billing.service.ordenes.dto.OrderSyncRecordDto;
import com.sistemaspalacios.billing.service.dto.ImportHistoryDto;
import com.sistemaspalacios.billing.service.dto.ImportResultDto;
import com.sistemaspalacios.billing.service.dto.InvoiceRefreshResult;
import com.sistemaspalacios.billing.service.dto.NitRecordDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OrderImportService {

    private static final Logger log = LoggerFactory.getLogger(OrderImportService.class);
    private static final Pattern PERIOD_CODE = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final OrderService orderService;
    private final RepositorioArchivoImportado repositorioArchivoImportado;
    private final RepositorioArchivoImportadoLinea repositorioArchivoImportadoLinea;
    private final RepositorioCliente repositorioCliente;
    private final RepositorioSede repositorioSede;
    private final OrderFileParser orderFileParser;
    private final String defaultSharedFolderPath;
    private final BillingService billingService;
    private final RepositorioPeriodo repositorioPeriodo;

    public OrderImportService(
            OrderService orderService,
            RepositorioArchivoImportado repositorioArchivoImportado,
            RepositorioArchivoImportadoLinea repositorioArchivoImportadoLinea,
            RepositorioCliente repositorioCliente,
            RepositorioSede repositorioSede,
            OrderFileParser orderFileParser,
            @org.springframework.beans.factory.annotation.Value("${siigo.carpeta-compartida.path:./storage/siigo_compartida}") String defaultSharedFolderPath,
            BillingService billingService,
            RepositorioPeriodo repositorioPeriodo
    ) {
        this.orderService = orderService;
        this.repositorioArchivoImportado = repositorioArchivoImportado;
        this.repositorioArchivoImportadoLinea = repositorioArchivoImportadoLinea;
        this.repositorioCliente = repositorioCliente;
        this.repositorioSede = repositorioSede;
        this.orderFileParser = orderFileParser;
        this.defaultSharedFolderPath = defaultSharedFolderPath;
        this.billingService = billingService;
        this.repositorioPeriodo = repositorioPeriodo;
    }

    /**
     * Import an Excel or flat file into the database with full traceability.
     */
    @Transactional
    public ImportResultDto importFile(
            String originalFileName,
            InputStream inputStream,
            String periodCode,
            OrderOrigin origin,
            String method
    ) {
        String effectivePeriod = (periodCode != null && !periodCode.isBlank())
                ? periodCode.trim()
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        OrderOrigin effectiveOrigin = origin != null ? origin : OrderOrigin.SPE;
        String effectiveMethod = method != null ? method : "EXCEL";

        ImportedFile importedFile = new ImportedFile(null, originalFileName, effectiveOrigin, effectiveMethod);
        importedFile.markProcessing();
        importedFile = repositorioArchivoImportado.save(importedFile);

        OrderFileParser.ParsedFile parsed;
        try {
            if (!PERIOD_CODE.matcher(effectivePeriod).matches()) {
                throw new IllegalArgumentException("El periodo '" + effectivePeriod + "' no es válido (formato AAAA-MM)");
            }
            parsed = orderFileParser.parse(originalFileName, inputStream, effectiveOrigin);
        } catch (Exception e) {
            log.error("Failed to parse file {}: {}", originalFileName, e.getMessage(), e);
            parsed = new OrderFileParser.ParsedFile(List.of(), "Error al leer el archivo: " + e.getMessage());
        }
        if (parsed.fileError() != null) {
            importedFile.markError(parsed.fileError());
            repositorioArchivoImportado.save(importedFile);
            return new ImportResultDto(
                    importedFile.getId(),
                    originalFileName,
                    effectiveMethod,
                    effectiveOrigin.name(),
                    "ERROR",
                    0,
                    1,
                    importedFile.getReceivedAt(),
                    importedFile.getProcessedAt(),
                    List.of(),
                    List.of(parsed.fileError())
            );
        }

        int totalCount = parsed.rows().size();
        int errorCount = 0;
        List<String> errorMessages = new ArrayList<>();

        Map<String, List<OrderSyncRecordDto>> recordsByNit = new LinkedHashMap<>();
        // Periodo → clientes con novedades: su factura de ese periodo se actualiza al final.
        Map<String, Set<UUID>> clientsWithNovelties = new TreeMap<>();

        // El número de línea es la fila real del archivo, para que el mensaje "Fila N" se
        // pueda ubicar en Excel.
        for (OrderFileParser.ParsedRow row : parsed.rows()) {
            ImportedFileLine lineEntity = new ImportedFileLine(null, importedFile.getId(), row.rowNumber(), row.rawJson());
            try {
                if (!row.isValid()) {
                    throw new IllegalArgumentException(row.error());
                }
                OrderSyncRecordDto record = row.record();
                rejectNoveltyAfterPeriod(record, effectivePeriod);

                String noveltyPeriod = noveltyPeriodFor(record, effectivePeriod);
                SubscriptionSite site = orderService.processRecord(record, noveltyPeriod);
                lineEntity.markSuccess(site.getId(), null);
                if (record.updateType() != null) {
                    clientsWithNovelties.computeIfAbsent(noveltyPeriod, k -> new LinkedHashSet<>()).add(site.getClientId());
                }
                recordsByNit.computeIfAbsent(record.nit(), k -> new ArrayList<>()).add(record);
            } catch (IllegalArgumentException e) {
                errorCount++;
                lineEntity.markError(e.getMessage());
                errorMessages.add("Fila " + row.rowNumber() + ": " + e.getMessage());
            }

            repositorioArchivoImportadoLinea.save(lineEntity);
        }

        // Las novedades se incorporan a la factura en preparación en esta misma transacción:
        // si la actualización falla, tampoco quedan registradas las novedades del archivo.
        List<String> resultMessages = new ArrayList<>(errorMessages);
        // En orden de periodo: un ajuste de un periodo emitido llega a la factura siguiente.
        clientsWithNovelties.forEach((period, clients) -> resultMessages.addAll(applyNoveltiesToEditableInvoices(clients, period)));

        importedFile.complete(totalCount, errorCount);
        importedFile = repositorioArchivoImportado.save(importedFile);

        List<NitRecordDto> nitSummaries = buildNitSummaries(recordsByNit);

        return new ImportResultDto(
                importedFile.getId(),
                originalFileName,
                effectiveMethod,
                effectiveOrigin.name(),
                importedFile.getStatus().name(),
                totalCount,
                errorCount,
                importedFile.getReceivedAt(),
                importedFile.getProcessedAt(),
                nitSummaries,
                resultMessages
        );
    }

    /**
     * Una novedad efectiva después del cierre del periodo no se cobra en él: el calculador la
     * ignora. Se rechaza la fila para que no quede registrada en un periodo equivocado sin aviso.
     * Una fecha anterior al inicio sí se admite (cambio retroactivo: aplica desde el día 1).
     */
    private void rejectNoveltyAfterPeriod(OrderSyncRecordDto record, String periodCode) {
        if (record.updateType() == null || record.updateEffectiveDate() == null) {
            return;
        }
        java.time.YearMonth period;
        try {
            period = java.time.YearMonth.parse(periodCode);
        } catch (RuntimeException ex) {
            return; // el periodo inválido lo reporta el procesamiento normal
        }
        if (record.updateEffectiveDate().isAfter(period.atEndOfMonth())) {
            throw new IllegalArgumentException(String.format(
                    "La novedad %s de la orden %s es efectiva el %s, después del cierre del periodo %s. Impórtela en el periodo %s.",
                    record.updateType(), record.externalOrderCode(), record.updateEffectiveDate(), periodCode,
                    java.time.YearMonth.from(record.updateEffectiveDate())));
        }
    }

    /**
     * Una novedad retroactiva (fecha efectiva anterior al periodo elegido) pertenece al periodo
     * de su fecha: si ese periodo ya estaba emitido, su impacto se lleva como ajuste a la
     * factura siguiente en lugar de cobrarse el mes actual completo al plan nuevo.
     */
    private static String noveltyPeriodFor(OrderSyncRecordDto record, String importPeriod) {
        if (record.updateType() == null || record.updateEffectiveDate() == null) {
            return importPeriod;
        }
        try {
            java.time.YearMonth selected = java.time.YearMonth.parse(importPeriod);
            java.time.YearMonth effective = java.time.YearMonth.from(record.updateEffectiveDate());
            return effective.isBefore(selected) ? effective.toString() : importPeriod;
        } catch (RuntimeException ex) {
            return importPeriod;
        }
    }

    private List<String> applyNoveltiesToEditableInvoices(Set<UUID> clientIds, String periodCode) {
        if (clientIds.isEmpty()) {
            return List.of();
        }
        BillingPeriod period = repositorioPeriodo.findByCode(periodCode).orElse(null);
        if (period == null) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        for (UUID clientId : clientIds) {
            InvoiceRefreshResult result = billingService.refreshEditableInvoice(clientId, period.getId());
            messages.add(result.toMessage());
        }
        return messages;
    }

    /**
     * Inspect folder configuration on the server.
     */
    @Transactional(readOnly = true)
    public com.sistemaspalacios.billing.service.dto.FolderConfigDto getFolderConfig(String folderPath) {
        String effectiveFolder = (folderPath != null && !folderPath.isBlank())
                ? folderPath.trim()
                : defaultSharedFolderPath;

        Path path = Paths.get(effectiveFolder);
        boolean exists = Files.exists(path) && Files.isDirectory(path);
        List<String> fileNames = new ArrayList<>();

        if (exists) {
            File dir = path.toFile();
            File[] files = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return !lower.startsWith("~$") && (lower.endsWith(".xlsx") || lower.endsWith(".xls")
                        || lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv")
                        || lower.endsWith(".jsonl"));
            });
            if (files != null) {
                for (File f : files) {
                    fileNames.add(f.getName());
                }
            }
        }

        return new com.sistemaspalacios.billing.service.dto.FolderConfigDto(
                defaultSharedFolderPath,
                path.toAbsolutePath().normalize().toString(),
                exists,
                fileNames.size(),
                fileNames
        );
    }

    /**
     * Import multiple files from a server folder path.
     */
    @Transactional
    public ImportResultDto importFromFolder(String folderPath, String periodCode, OrderOrigin origin) {
        String effectiveFolder = (folderPath != null && !folderPath.isBlank())
                ? folderPath.trim()
                : defaultSharedFolderPath;

        Path path = Paths.get(effectiveFolder);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            try {
                Files.createDirectories(path);
            } catch (Exception e) {
                log.warn("Could not create folder {}: {}", effectiveFolder, e.getMessage());
            }
        }

        File dir = path.toFile();
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return !lower.startsWith("~$") && (lower.endsWith(".xlsx") || lower.endsWith(".xls")
                    || lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv")
                    || lower.endsWith(".jsonl"));
        });

        if (files == null || files.length == 0) {
            // If empty folder, create an audit record indicating 0 files found
            ImportedFile emptyFile = new ImportedFile(
                    null,
                    "Consumo de carpeta (" + (path.getFileName() != null ? path.getFileName() : effectiveFolder) + ")",
                    origin != null ? origin : OrderOrigin.SPE,
                    "CARPETA"
            );
            emptyFile.complete(0, 0);
            repositorioArchivoImportado.save(emptyFile);

            return new ImportResultDto(
                    emptyFile.getId(),
                    emptyFile.getFileName(),
                    "Consumo de carpeta",
                    emptyFile.getOrigin().name(),
                    "PROCESADO",
                    0,
                    0,
                    emptyFile.getReceivedAt(),
                    emptyFile.getProcessedAt(),
                    List.of(),
                    List.of("No se encontraron archivos pendientes (.xlsx, .csv, .txt) en la carpeta")
            );
        }

        int totalAll = 0;
        int errorAll = 0;
        List<NitRecordDto> allSummaries = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        UUID lastImportId = null;

        for (File f : files) {
            try (InputStream is = new FileInputStream(f)) {
                ImportResultDto res = importFile(f.getName(), is, periodCode, origin, "CARPETA");
                totalAll += res.totalRecords();
                errorAll += res.errorRecords();
                allSummaries.addAll(res.records());
                messages.addAll(res.messages());
                lastImportId = res.id();
            } catch (Exception e) {
                log.error("Error reading file {} from folder: {}", f.getName(), e.getMessage());
                messages.add("Error en archivo " + f.getName() + ": " + e.getMessage());
                errorAll++;
            }
        }

        return new ImportResultDto(
                lastImportId != null ? lastImportId : UUID.randomUUID(),
                "Carpeta (" + files.length + " archivos procesados)",
                "Consumo de carpeta",
                (origin != null ? origin : OrderOrigin.SPE).name(),
                errorAll > 0 && totalAll == 0 ? "ERROR" : "PROCESADO",
                totalAll,
                errorAll,
                java.time.Instant.now(),
                java.time.Instant.now(),
                allSummaries,
                messages
        );
    }

    /**
     * Get list of historical imports from database.
     */
    @Transactional(readOnly = true)
    public List<ImportHistoryDto> getHistory() {
        return repositorioArchivoImportado.findAllByOrderByReceivedAtDesc().stream()
                .map(ImportHistoryDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get recent imported records grouped by NIT from database.
     */
    @Transactional(readOnly = true)
    public List<NitRecordDto> getRecentRecords() {
        List<CorporateClient> clients = repositorioCliente.findAll();
        List<NitRecordDto> result = new ArrayList<>();

        for (CorporateClient client : clients) {
            List<SubscriptionSite> sites = repositorioSede.findByClientId(client.getId());
            BigDecimal total = sites.stream()
                    .map(s -> s.getMonthlyValue() != null ? s.getMonthlyValue().amount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<String> services = sites.stream()
                    .map(s -> s.getSiteName() != null ? s.getSiteName() : "Sede " + s.getExternalOrderCode())
                    .limit(5)
                    .collect(Collectors.toList());

            result.add(new NitRecordDto(
                    client.getNit(),
                    client.getBusinessName() != null ? client.getBusinessName() : "NIT " + client.getNit(),
                    sites.size(),
                    formatCurrency(total),
                    "Válido",
                    services
            ));
        }

        return result;
    }

    private List<NitRecordDto> buildNitSummaries(Map<String, List<OrderSyncRecordDto>> recordsByNit) {
        List<NitRecordDto> list = new ArrayList<>();
        for (Map.Entry<String, List<OrderSyncRecordDto>> entry : recordsByNit.entrySet()) {
            String nit = entry.getKey();
            List<OrderSyncRecordDto> items = entry.getValue();
            String clientName = items.stream().map(OrderSyncRecordDto::businessName)
                    .filter(Objects::nonNull).findFirst().orElse("NIT " + nit);
            int branchesCount = items.size();
            BigDecimal total = items.stream()
                    .map(OrderSyncRecordDto::monthlyValue)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<String> services = items.stream()
                    .map(it -> it.siteName() != null ? it.siteName() : "Sede " + it.externalOrderCode())
                    .collect(Collectors.toList());

            list.add(new NitRecordDto(
                    nit,
                    clientName,
                    branchesCount,
                    formatCurrency(total),
                    "Válido",
                    services
            ));
        }
        return list;
    }

    private String formatCurrency(BigDecimal amount) {
        if (amount == null) return "$ 0";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("es", "CO"));
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("$ #,##0", symbols);
        return df.format(amount);
    }
}
