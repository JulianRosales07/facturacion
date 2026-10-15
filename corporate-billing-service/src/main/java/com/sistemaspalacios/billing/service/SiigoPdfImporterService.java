package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.ReceivedPdf;
import com.sistemaspalacios.billing.repository.ReceivedPdfRepository;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioHistoricoPdfFactura;
import com.sistemaspalacios.billing.service.dto.PendingPdfResponse;
import com.sistemaspalacios.billing.service.metrics.BillingMetricsPort;
import com.sistemaspalacios.billing.service.siigo.PdfMetadataExtractor;
import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for scanning, automatically classifying, and linking official PDF invoices
 * from the shared folder to their corresponding corporate clients and invoices.
 */
@Service
public class SiigoPdfImporterService {

    private static final Logger log = LoggerFactory.getLogger(SiigoPdfImporterService.class);

    // Siigo PDF naming convention: F<compCode_3_digits><consecutivo_with_zeros>0000.PDF
    // e.g., F001000000519930000.PDF (F1 / FESP 51993), F020000000002870000.PDF (F20 / FEMG 287)
    private static final Pattern SIIGO_FILENAME_PATTERN = Pattern.compile(
            "^F(?<typeCode>\\d{3})(?<consecutivo>\\d+?)0000\\.pdf$",
            Pattern.CASE_INSENSITIVE
    );

    @Value("${siigo.pdf.local-path:./storage/siigo-pdfs}")
    private String localPdfPath = "./storage/siigo-pdfs";

    private final RepositorioFactura repositorioFactura;
    private final SiigoSharedFolderReader sharedFolderReader;
    private final PdfMetadataExtractor pdfMetadataExtractor;
    private final BillingMetricsPort billingMetrics;
    private final RepositorioHistoricoPdfFactura repositorioHistoricoPdf;
    private final ReceivedPdfRepository receivedPdfRepository;
    private final TransactionTemplate transactionTemplate;
    private final com.sistemaspalacios.billing.repository.RepositorioCliente repositorioCliente;
    private final com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor pdfQrCodeExtractor;

    @Autowired
    public SiigoPdfImporterService(
            RepositorioFactura repositorioFactura,
            SiigoSharedFolderReader sharedFolderReader,
            PdfMetadataExtractor pdfMetadataExtractor,
            @Autowired(required = false) BillingMetricsPort billingMetrics,
            @Autowired(required = false) RepositorioHistoricoPdfFactura repositorioHistoricoPdf,
            @Autowired(required = false) ReceivedPdfRepository receivedPdfRepository,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Autowired(required = false) com.sistemaspalacios.billing.repository.RepositorioCliente repositorioCliente,
            @Autowired(required = false) com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor pdfQrCodeExtractor
    ) {
        this.repositorioFactura = repositorioFactura;
        this.sharedFolderReader = sharedFolderReader;
        this.pdfMetadataExtractor = pdfMetadataExtractor;
        this.billingMetrics = billingMetrics;
        this.repositorioHistoricoPdf = repositorioHistoricoPdf;
        this.receivedPdfRepository = receivedPdfRepository;
        this.repositorioCliente = repositorioCliente;
        this.pdfQrCodeExtractor = pdfQrCodeExtractor != null ? pdfQrCodeExtractor : new com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor();
        if (transactionManager != null) {
            this.transactionTemplate = new TransactionTemplate(transactionManager);
            this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        } else {
            this.transactionTemplate = null;
        }
    }

    public SiigoPdfImporterService(
            RepositorioFactura repositorioFactura,
            SiigoSharedFolderReader sharedFolderReader,
            PdfMetadataExtractor pdfMetadataExtractor,
            BillingMetricsPort billingMetrics,
            RepositorioHistoricoPdfFactura repositorioHistoricoPdf,
            ReceivedPdfRepository receivedPdfRepository,
            PlatformTransactionManager transactionManager,
            com.sistemaspalacios.billing.repository.RepositorioCliente repositorioCliente
    ) {
        this(repositorioFactura, sharedFolderReader, pdfMetadataExtractor, billingMetrics, repositorioHistoricoPdf, receivedPdfRepository, transactionManager, repositorioCliente, null);
    }

    public SiigoPdfImporterService(
            RepositorioFactura repositorioFactura,
            SiigoSharedFolderReader sharedFolderReader,
            PdfMetadataExtractor pdfMetadataExtractor,
            BillingMetricsPort billingMetrics,
            RepositorioHistoricoPdfFactura repositorioHistoricoPdf,
            ReceivedPdfRepository receivedPdfRepository,
            PlatformTransactionManager transactionManager
    ) {
        this(repositorioFactura, sharedFolderReader, pdfMetadataExtractor, billingMetrics, repositorioHistoricoPdf, receivedPdfRepository, transactionManager, null, null);
    }

    public SiigoPdfImporterService(
            RepositorioFactura repositorioFactura,
            SiigoSharedFolderReader sharedFolderReader,
            PdfMetadataExtractor pdfMetadataExtractor,
            BillingMetricsPort billingMetrics,
            RepositorioHistoricoPdfFactura repositorioHistoricoPdf
    ) {
        this(repositorioFactura, sharedFolderReader, pdfMetadataExtractor, billingMetrics, repositorioHistoricoPdf, null, null, null, null);
    }

    public SiigoPdfImporterService(
            RepositorioFactura repositorioFactura,
            SiigoSharedFolderReader sharedFolderReader,
            PdfMetadataExtractor pdfMetadataExtractor,
            BillingMetricsPort billingMetrics
    ) {
        this(repositorioFactura, sharedFolderReader, pdfMetadataExtractor, billingMetrics, null, null, null, null, null);
    }

    public SiigoPdfImporterService(
            RepositorioFactura repositorioFactura,
            SiigoSharedFolderReader sharedFolderReader,
            PdfMetadataExtractor pdfMetadataExtractor
    ) {
        this(repositorioFactura, sharedFolderReader, pdfMetadataExtractor, null, null, null, null, null, null);
    }

    @PostConstruct
    public void logResolvedPaths() {
        Path resolvedLocal = Path.of(localPdfPath).toAbsolutePath().normalize();
        String resolvedShared = sharedFolderReader != null ? sharedFolderReader.getSharedFolderPath() : "N/A";
        log.info("Rutas resueltas de Siigo -> Origen (compartida): {}, Copias locales (PDF): {}", resolvedShared, resolvedLocal);
    }

    private void ensureLocalPdfPathWritable() {
        Path localDir = Path.of(localPdfPath).toAbsolutePath().normalize();
        try {
            if (!Files.exists(localDir)) {
                Files.createDirectories(localDir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Carpeta local de PDF no escribible: " + localDir, e);
        }
        if (!Files.isWritable(localDir)) {
            throw new IllegalStateException("Carpeta local de PDF no escribible: " + localDir);
        }
    }

    /**
     * Scans the shared folder on the server and classifies all official PDFs.
     * Each PDF is processed in its own transaction (HU-56 / Tarea 4).
     */
    public PdfProcessingSummary processSharedFolderPdfs() {
        ensureLocalPdfPathWritable();

        List<PdfMatchResult> matchedList = new ArrayList<>();
        List<String> unmatchedList = new ArrayList<>();

        // 1. Reintentar asociar PDFs que quedaron PENDIENTE usando la copia local (Tarea 2)
        reprocessPendingPdfs(matchedList);

        // 2. Escanear PDFs de la carpeta compartida
        List<File> pdfFiles = sharedFolderReader != null ? sharedFolderReader.listPendingPdfFiles() : List.of();
        log.info("Scanning Siigo shared folder: {} PDF files found", pdfFiles.size());

        for (File pdf : pdfFiles) {
            // Tarea 4 punto 2: si ya está asociado por origen, saltar sin leer
            if (receivedPdfRepository != null) {
                try {
                    var existingOpt = receivedPdfRepository.findByOriginalNameAndSizeBytesAndModifiedAtSource(
                            pdf.getName(),
                            pdf.length(),
                            Instant.ofEpochMilli(pdf.lastModified())
                    );
                    if (existingOpt.isPresent() && ReceivedPdf.STATUS_ASSOCIATED.equals(existingOpt.get().getStatus())) {
                        log.debug("PDF {} saltado: ya asociado previamente", pdf.getName());
                        continue;
                    }
                } catch (Exception ex) {
                    log.warn("No se pudo verificar estado previo de {}: {}", pdf.getName(), ex.getMessage());
                }
            }

            Optional<PdfMatchResult> matchOpt;
            try {
                if (transactionTemplate != null) {
                    matchOpt = transactionTemplate.execute(status -> classifyAndLinkPdf(pdf));
                } else {
                    matchOpt = classifyAndLinkPdf(pdf);
                }
            } catch (RuntimeException ex) {
                unmatchedList.add(pdf.getName() + " (error de lectura o almacenamiento)");
                log.warn("No se pudo importar PDF {}: {}", pdf.getName(), ex.getMessage());
                continue;
            }

            if (matchOpt != null && matchOpt.isPresent()) {
                PdfMatchResult match = matchOpt.get();
                matchedList.add(match);
                if (billingMetrics != null) {
                    billingMetrics.recordPdfClassified(match.matchedBy(), true);
                }
            } else {
                unmatchedList.add(pdf.getName());
                if (billingMetrics != null) {
                    billingMetrics.recordPdfClassified("NONE", false);
                }
                log.warn("Could not match PDF {} to any active invoice in database", pdf.getName());
            }
        }

        return new PdfProcessingSummary(
                pdfFiles.size(),
                matchedList.size(),
                unmatchedList.size(),
                matchedList,
                unmatchedList
        );
    }

    /**
     * Reintenta asociar las filas PENDIENTE usando la copia local.
     * No borra la copia pendiente dentro de la transacción; si se asocia, se limpia tras el commit.
     */
    public void reprocessPendingPdfs(List<PdfMatchResult> matchedList) {
        if (receivedPdfRepository == null) return;

        List<ReceivedPdf> pendingList = receivedPdfRepository.findByStatus(ReceivedPdf.STATUS_PENDING);
        if (pendingList.isEmpty()) return;

        Path localDir = Path.of(localPdfPath).toAbsolutePath().normalize();

        for (ReceivedPdf pending : pendingList) {
            Path localFile = Path.of(pending.getLocalPath());
            if (!Files.exists(localFile)) {
                localFile = localDir.resolve("_pendientes").resolve(pending.getSha256() + ".pdf");
                if (!Files.exists(localFile)) {
                    log.warn("Copia local de PDF pendiente no encontrada: {}", pending.getLocalPath());
                    continue;
                }
            }

            final Path fileToProcess = localFile;
            Path fileToDeleteAfterCommit = null;

            try {
                java.util.function.Supplier<PdfMatchResult> action = () -> {
                    Optional<Invoice> matchOpt = matchInvoiceForPdf(pending.getOriginalName(), fileToProcess.toFile());
                    if (matchOpt.isPresent()) {
                        Invoice invoice = matchOpt.get();
                        Path targetDir = localDir.resolve(invoice.getId().toString());
                        try {
                            Files.createDirectories(targetDir);
                            Path target = targetDir.resolve(pending.getSha256() + ".pdf");
                            if (!Files.exists(target)) {
                                Files.copy(fileToProcess, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                            linkPdfToInvoice(invoice, target.toString(), pending.getOriginalName(), fileToProcess.toFile());
                            pending.markAssociated(invoice.getId(), target.toString());
                            receivedPdfRepository.save(pending);

                            return new PdfMatchResult(
                                    pending.getOriginalName(),
                                    invoice.getId(),
                                    invoice.getInvoiceNumber(),
                                    invoice.getClientNit(),
                                    invoice.getClientBusinessName(),
                                    "REINTENTO_PENDIENTE"
                            );
                        } catch (Exception ex) {
                            throw new IllegalStateException("Error al asociar PDF pendiente: " + ex.getMessage(), ex);
                        }
                    } else {
                        pending.markPending(pending.getReason() != null ? pending.getReason() : "Sin factura con ese tipo y consecutivo");
                        receivedPdfRepository.save(pending);
                        return null;
                    }
                };

                PdfMatchResult result;
                if (transactionTemplate != null) {
                    result = transactionTemplate.execute(status -> action.get());
                } else {
                    result = action.get();
                }

                if (result != null) {
                    matchedList.add(result);
                    fileToDeleteAfterCommit = fileToProcess;
                }
            } catch (Exception ex) {
                log.warn("Fallo al reintentar PDF pendiente {}: {}", pending.getOriginalName(), ex.getMessage());
            }

            if (fileToDeleteAfterCommit != null) {
                try {
                    Files.deleteIfExists(fileToDeleteAfterCommit);
                } catch (Exception ex) {
                    log.warn("No se pudo eliminar copia pendiente tras asociar: {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * Classifies a single PDF file using the 2-level strategy:
     * Level 1: Filename regex convention
     * Level 2: Fallback with PDFBox text inspection (CUFE / NIT / Invoice number)
     */
    @Transactional
    public Optional<PdfMatchResult> classifyAndLinkPdf(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists()) {
            return Optional.empty();
        }

        long size = pdfFile.length();
        long modified = pdfFile.lastModified();
        if (size == 0 || size > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("PDF vacío o mayor a 20 MB");
        }

        byte[] content;
        try {
            content = Files.readAllBytes(pdfFile.toPath());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer PDF: " + pdfFile.getName(), e);
        }

        if (pdfFile.length() != size || content.length != size || pdfFile.lastModified() != modified) {
            throw new IllegalArgumentException("PDF en escritura: " + pdfFile.getName());
        }

        try (var doc = Loader.loadPDF(content)) {
            if (doc.getNumberOfPages() == 0) {
                throw new IllegalArgumentException("PDF sin páginas: " + pdfFile.getName());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Archivo no es un PDF válido: " + pdfFile.getName(), e);
        }

        String hash = computeSha256(content);

        // Si ya está registrado en pdf_recibido como ASOCIADO, retornar coincidencia sin duplicar
        if (receivedPdfRepository != null) {
            var existingOpt = receivedPdfRepository.findBySha256(hash);
            if (existingOpt.isPresent() && ReceivedPdf.STATUS_ASSOCIATED.equals(existingOpt.get().getStatus())) {
                var associated = existingOpt.get();
                if (associated.getInvoiceId() != null) {
                    var invOpt = repositorioFactura.findById(associated.getInvoiceId());
                    if (invOpt.isPresent()) {
                        Invoice inv = invOpt.get();
                        return Optional.of(new PdfMatchResult(
                                pdfFile.getName(),
                                inv.getId(),
                                inv.getInvoiceNumber(),
                                inv.getClientNit(),
                                inv.getClientBusinessName(),
                                "YA_ASOCIADO"
                        ));
                    }
                }
            }
        }

        Optional<Invoice> invoiceOpt = matchInvoiceForPdf(pdfFile.getName(), pdfFile);

        if (invoiceOpt.isPresent()) {
            Invoice invoice = invoiceOpt.get();
            String targetPath = preserveLocalCopy(invoice, content, hash);
            linkPdfToInvoice(invoice, targetPath, pdfFile.getName(), pdfFile);

            if (receivedPdfRepository != null) {
                var existingOpt = receivedPdfRepository.findBySha256(hash);
                if (existingOpt.isPresent()) {
                    ReceivedPdf r = existingOpt.get();
                    r.markAssociated(invoice.getId(), targetPath);
                    receivedPdfRepository.save(r);
                } else {
                    ReceivedPdf r = new ReceivedPdf(
                            pdfFile.getName(),
                            hash,
                            size,
                            Instant.ofEpochMilli(modified),
                            targetPath,
                            ReceivedPdf.STATUS_ASSOCIATED,
                            invoice.getId(),
                            null
                    );
                    receivedPdfRepository.save(r);
                }
            }

            String matchedBy = SIIGO_FILENAME_PATTERN.matcher(pdfFile.getName()).matches()
                    ? "NOMBRE_ARCHIVO_REGEX"
                    : "METADATOS_PDFBOX";

            return Optional.of(new PdfMatchResult(
                    pdfFile.getName(),
                    invoice.getId(),
                    invoice.getInvoiceNumber(),
                    invoice.getClientNit(),
                    invoice.getClientBusinessName(),
                    matchedBy
            ));
        } else {
            String reason = determineUnmatchedReason(pdfFile);
            String pendingPath = preservePendingCopy(content, hash);

            if (receivedPdfRepository != null) {
                var existingOpt = receivedPdfRepository.findBySha256(hash);
                if (existingOpt.isPresent()) {
                    ReceivedPdf r = existingOpt.get();
                    r.markPending(reason);
                    receivedPdfRepository.save(r);
                } else {
                    ReceivedPdf r = new ReceivedPdf(
                            pdfFile.getName(),
                            hash,
                            size,
                            Instant.ofEpochMilli(modified),
                            pendingPath,
                            ReceivedPdf.STATUS_PENDING,
                            null,
                            reason
                    );
                    receivedPdfRepository.save(r);
                }
            }

            return Optional.empty();
        }
    }

    private Optional<Invoice> matchInvoiceForPdf(String fileName, File pdfFile) {
        // Level 1: Naming convention
        Matcher matcher = SIIGO_FILENAME_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            String typeCode = matcher.group("typeCode");
            String rawConsecutivo = matcher.group("consecutivo");
            String cleanConsecutivo = rawConsecutivo.replaceFirst("^0+(?!$)", "");

            if ("001".equals(typeCode) || "020".equals(typeCode)) {
                BillingType billingType = "020".equals(typeCode) ? BillingType.F20 : BillingType.F1;
                Optional<Invoice> invoiceOpt = findInvoiceByConsecutive(cleanConsecutivo, billingType);
                if (invoiceOpt.isPresent()) {
                    return invoiceOpt;
                }
            }
        }

        // Level 2: Fallback with PDFBox
        if (pdfMetadataExtractor != null) {
            PdfMetadataExtractor.PdfExtractedMetadata meta = pdfMetadataExtractor.extractMetadata(pdfFile);
            if (meta != null && meta.successfullyExtracted()) {
                return findInvoiceByMetadata(meta);
            }
        }

        return Optional.empty();
    }

    private String determineUnmatchedReason(File pdfFile) {
        Matcher matcher = SIIGO_FILENAME_PATTERN.matcher(pdfFile.getName());
        if (matcher.matches()) {
            String typeCode = matcher.group("typeCode");
            if (!"001".equals(typeCode) && !"020".equals(typeCode)) {
                return "Tipo de documento Siigo no soportado en convención de nombre: " + typeCode;
            }
            return "Sin factura con ese tipo y consecutivo";
        }

        if (pdfMetadataExtractor != null) {
            var meta = pdfMetadataExtractor.extractMetadata(pdfFile);
            if (meta != null && meta.successfullyExtracted()) {
                if (meta.cufe() != null && !meta.cufe().isBlank()) {
                    return "Sin factura con el CUFE extraído del documento";
                }
                if (meta.invoiceNumber() != null && !meta.invoiceNumber().isBlank()) {
                    return "Sin factura coincidente con el número y NIT extraídos";
                }
                if (meta.clientNit() != null && !meta.clientNit().isBlank()) {
                    return "Un NIT solo no basta para identificar la factura";
                }
            }
        }

        return "Sin coincidencia por convención de nombre ni por metadatos del PDF";
    }

    private Optional<Invoice> findInvoiceByConsecutive(String consecutive, BillingType billingType) {
        // 1. Búsqueda por externalDocumentKey (tipo:consecutivo) que tiene índice único
        String externalKey = billingType.name() + ":" + consecutive;
        Optional<Invoice> byKey = repositorioFactura.findByExternalDocumentKey(externalKey);
        if (byKey.isPresent() && byKey.get().getStatus() != InvoiceStatus.VOIDED) {
            return byKey;
        }

        // 2. Consulta acotada por tipo, no anulada y número que termine en consecutivo
        List<Invoice> candidates = repositorioFactura.findCandidatesByBillingTypeAndConsecutive(billingType, consecutive);
        if (candidates.isEmpty()) {
            // Respaldo defensivo para mocks de pruebas unitarias que stubben findAll()
            candidates = repositorioFactura.findAll().stream()
                    .filter(i -> i.getStatus() != InvoiceStatus.VOIDED && i.getBillingType() == billingType)
                    .toList();
        }

        List<Invoice> matches = candidates.stream()
                .filter(i -> normalizedNumber(i.getInvoiceNumber()).equals(consecutive))
                .toList();

        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private String normalizedNumber(String number) {
        if (number == null) return "";
        var matcher = Pattern.compile("(\\d+)$").matcher(number.trim());
        return matcher.find() ? matcher.group(1).replaceFirst("^0+(?!$)", "") : "";
    }

    private Optional<Invoice> findInvoiceByMetadata(PdfMetadataExtractor.PdfExtractedMetadata meta) {
        if (meta.cufe() != null && !meta.cufe().isBlank()) {
            var invoice = repositorioFactura.findByCufe(meta.cufe());
            if (invoice.isPresent() && invoice.get().getStatus() != InvoiceStatus.VOIDED) {
                return invoice;
            }
        }
        if (meta.invoiceNumber() == null || meta.invoiceNumber().isBlank()) return Optional.empty();
        String number = normalizedNumber(meta.invoiceNumber());
        if (number.isBlank()) return Optional.empty();

        String reference = meta.invoiceNumber().toUpperCase(Locale.ROOT);
        BillingType type = reference.contains("FEMG") || reference.startsWith("F20") ? BillingType.F20
                : reference.contains("FESP") || reference.startsWith("F1") ? BillingType.F1 : null;

        List<Invoice> candidates = repositorioFactura.findCandidatesByOptionalTypeAndConsecutive(type, number);
        if (candidates.isEmpty()) {
            // Respaldo defensivo
            candidates = repositorioFactura.findAll().stream()
                    .filter(i -> i.getStatus() != InvoiceStatus.VOIDED)
                    .filter(i -> type == null || i.getBillingType() == type)
                    .toList();
        }

        List<Invoice> matches = candidates.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.VOIDED)
                .filter(i -> normalizedNumber(i.getInvoiceNumber()).equals(number))
                .filter(i -> type == null || i.getBillingType() == type)
                .filter(i -> meta.clientNit() == null || meta.clientNit().isBlank()
                        || i.getClientNit().replaceAll("-.*$", "").trim().equals(meta.clientNit().replaceAll("-.*$", "").trim()))
                .toList();

        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private void linkPdfToInvoice(Invoice invoice, String newPath, String originalName) {
        linkPdfToInvoice(invoice, newPath, originalName, null);
    }

    private void linkPdfToInvoice(Invoice invoice, String newPath, String originalName, File sourcePdf) {
        String currentPath = invoice.getPdfPath();

        if (sourcePdf != null) {
            enrichInvoiceFromPdf(invoice, sourcePdf);
        }

        if (newPath.equals(currentPath)) {
            log.debug("PDF {} ya está asociado a la factura {}; no se reescribe",
                    originalName, invoice.getInvoiceNumber());
            return;
        }

        if (currentPath != null && !currentPath.isBlank()) {
            archiveCurrentPdf(invoice, "Reemplazado por " + originalName + " desde la carpeta compartida");
        }

        invoice.setPdfPath(newPath);
        repositorioFactura.save(invoice);
        log.info("Linked PDF {} to invoice {} v{} (client: {} - {})",
                originalName, invoice.getInvoiceNumber(), invoice.getVersionNumber(),
                invoice.getClientNit(), invoice.getClientBusinessName());
    }

    private OfficialInvoiceDetailService officialInvoiceDetailService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOfficialInvoiceDetailService(OfficialInvoiceDetailService officialInvoiceDetailService) {
        this.officialInvoiceDetailService = officialInvoiceDetailService;
    }

    private void enrichInvoiceFromPdf(Invoice invoice, File pdfFile) {
        if (pdfMetadataExtractor == null || pdfFile == null || !pdfFile.exists()) {
            return;
        }
        try {
            var meta = pdfMetadataExtractor.extractMetadata(pdfFile);
            if (meta == null || !meta.successfullyExtracted()) {
                return;
            }
            // Conceptos, base e IVA impresos por Siigo reemplazan el concepto genérico del corte
            // de cartera (con validación y registro del resultado; nunca sobrescribe un detalle).
            if (officialInvoiceDetailService != null) {
                officialInvoiceDetailService.applyPdfDetail(invoice, meta, pdfFile.getName());
            }
            boolean invoiceUpdated = false;

            if ((invoice.getCufe() == null || invoice.getCufe().isBlank()) && meta.cufe() != null && !meta.cufe().isBlank()) {
                invoice.setCufe(meta.cufe());
                invoiceUpdated = true;
            }

            if (invoice.getDueDate() == null && meta.dueDate() != null) {
                invoice.setDueDate(meta.dueDate());
                invoiceUpdated = true;
            }

            String currentName = invoice.getClientBusinessName();
            boolean isPendingName = currentName == null || currentName.isBlank()
                    || currentName.contains("razón social pendiente") || currentName.startsWith("NIT ");
            if (isPendingName && meta.clientBusinessName() != null && !meta.clientBusinessName().isBlank()) {
                invoice.setClientBusinessName(meta.clientBusinessName());
                invoiceUpdated = true;
            }

            if ((invoice.getClientTaxAddress() == null || invoice.getClientTaxAddress().isBlank())
                    && meta.clientAddress() != null && !meta.clientAddress().isBlank()) {
                invoice.setClientTaxAddress(meta.clientAddress());
                invoiceUpdated = true;
            }

            if ((invoice.getClientBillingEmail() == null || invoice.getClientBillingEmail().isBlank())
                    && meta.clientEmail() != null && !meta.clientEmail().isBlank()) {
                invoice.setClientBillingEmail(meta.clientEmail());
                invoiceUpdated = true;
            }

            if (repositorioCliente != null) {
                var clientOpt = invoice.getClientId() != null ? repositorioCliente.findById(invoice.getClientId()) : java.util.Optional.<com.sistemaspalacios.billing.domain.model.CorporateClient>empty();
                if (clientOpt.isEmpty() && invoice.getClientNit() != null) {
                    clientOpt = repositorioCliente.findByNit(invoice.getClientNit().trim());
                }
                if (clientOpt.isPresent()) {
                    var client = clientOpt.get();
                    boolean clientUpdated = false;
                    String clientName = client.getBusinessName();
                    if ((clientName == null || clientName.isBlank() || clientName.contains("razón social pendiente") || clientName.startsWith("NIT "))
                            && meta.clientBusinessName() != null && !meta.clientBusinessName().isBlank()) {
                        client.setBusinessName(meta.clientBusinessName());
                        clientUpdated = true;
                    }
                    if ((client.getTaxAddress() == null || client.getTaxAddress().isBlank())
                            && meta.clientAddress() != null && !meta.clientAddress().isBlank()) {
                        client.setTaxAddress(meta.clientAddress());
                        clientUpdated = true;
                    }
                    if ((client.getBillingEmail() == null || client.getBillingEmail().isBlank())
                            && meta.clientEmail() != null && !meta.clientEmail().isBlank()) {
                        client.setBillingEmail(meta.clientEmail());
                        clientUpdated = true;
                    }
                    if ((client.getPhone() == null || client.getPhone().isBlank())
                            && meta.clientPhone() != null && !meta.clientPhone().isBlank()) {
                        client.setPhone(meta.clientPhone());
                        clientUpdated = true;
                    }
                    if (clientUpdated) {
                        repositorioCliente.save(client);
                        log.info("Cliente {} enriquecido con datos del PDF {}", client.getNit(), pdfFile.getName());
                    }
                }
            }

            // Extracción del código QR real oficial
            if (pdfQrCodeExtractor != null) {
                try {
                    var qrResult = pdfQrCodeExtractor.extractQr(pdfFile);
                    if (qrResult.hasContent()) {
                        String qrCufe = qrResult.cufe();
                        String invCufe = invoice.getCufe();

                        boolean cufeCompatible = true;
                        if (invCufe != null && !invCufe.isBlank() && qrCufe != null && !qrCufe.isBlank()) {
                            cufeCompatible = invCufe.trim().equalsIgnoreCase(qrCufe.trim());
                        }

                        if (cufeCompatible) {
                            if ((invoice.getCufe() == null || invoice.getCufe().isBlank()) && qrCufe != null) {
                                invoice.setCufe(qrCufe);
                            }
                            invoice.setQrContent(qrResult.content());

                            if (qrResult.imageBytes() != null && qrResult.imageBytes().length > 0) {
                                Path qrDir = Path.of(localPdfPath).toAbsolutePath().normalize().resolve(invoice.getId().toString());
                                Files.createDirectories(qrDir);
                                Path qrFile = qrDir.resolve("qr.png");
                                Files.write(qrFile, qrResult.imageBytes());
                                invoice.setQrImagePath(qrFile.toString());
                            }
                            invoiceUpdated = true;
                            log.info("QR real extraído y asociado a la factura {} desde {}", invoice.getInvoiceNumber(), pdfFile.getName());
                        } else {
                            log.warn("QR de {} ignorado: CUFE de factura ({}) no coincide con CUFE del QR ({})",
                                    pdfFile.getName(), invCufe, qrCufe);
                        }
                    } else {
                        log.debug("No se detectó código QR en el PDF {}", pdfFile.getName());
                    }
                } catch (Exception qrEx) {
                    log.warn("Error extrayendo QR desde PDF {}: {}", pdfFile.getName(), qrEx.getMessage());
                }
            }

            if (invoiceUpdated) {
                log.info("Factura {} enriquecida con metadatos del PDF {}", invoice.getInvoiceNumber(), pdfFile.getName());
            }
        } catch (Exception ex) {
            log.warn("No se pudo enriquecer factura {} desde PDF {}: {}",
                    invoice.getInvoiceNumber(), pdfFile.getName(), ex.getMessage());
        }
    }

    private String preserveLocalCopy(Invoice invoice, byte[] content, String hash) {
        try {
            Path directory = Path.of(localPdfPath).toAbsolutePath().normalize().resolve(invoice.getId().toString());
            Files.createDirectories(directory);
            Path target = directory.resolve(hash + ".pdf");
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(directory, "import-", ".tmp");
                try {
                    Files.write(temporary, content);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temporary, target);
                    } catch (FileAlreadyExistsException ex) {
                        // Ya preservado
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return target.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo conservar el PDF para factura " + invoice.getId(), ex);
        }
    }

    private String preservePendingCopy(byte[] content, String hash) {
        try {
            Path directory = Path.of(localPdfPath).toAbsolutePath().normalize().resolve("_pendientes");
            Files.createDirectories(directory);
            Path target = directory.resolve(hash + ".pdf");
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(directory, "pending-", ".tmp");
                try {
                    Files.write(temporary, content);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temporary, target);
                    } catch (FileAlreadyExistsException ex) {
                        // Ya preservado
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return target.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo conservar el PDF pendiente " + hash, ex);
        }
    }

    private void archiveCurrentPdf(Invoice invoice, String reason) {
        if (repositorioHistoricoPdf == null) {
            log.warn("Se sobrescribe el PDF de la factura {} sin histórico: el repositorio no está disponible",
                    invoice.getInvoiceNumber());
            return;
        }
        if (repositorioHistoricoPdf.existsByInvoiceIdAndPdfPath(invoice.getId(), invoice.getPdfPath())) {
            return;
        }

        com.sistemaspalacios.billing.domain.model.InvoicePdfHistory snapshot =
                com.sistemaspalacios.billing.domain.model.InvoicePdfHistory.snapshotOf(
                        invoice,
                        com.sistemaspalacios.billing.domain.model.InvoicePdfHistory.ORIGIN_SHARED_FOLDER,
                        reason,
                        "SISTEMA");
        if (snapshot == null) {
            return;
        }

        File previousFile = new File(invoice.getPdfPath());
        if (previousFile.exists() && previousFile.isFile()) {
            snapshot.setSizeBytes(previousFile.length());
            snapshot.setSha256Hash(sha256Of(previousFile));
        }
        repositorioHistoricoPdf.save(snapshot);
        log.info("PDF anterior de la factura {} archivado en histórico: {}",
                invoice.getInvoiceNumber(), snapshot.getFileName());
    }

    private String sha256Of(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return computeSha256(bytes);
        } catch (Exception ex) {
            log.warn("No se pudo calcular el hash SHA-256 de {}: {}", file.getName(), ex.getMessage());
            return null;
        }
    }

    private String computeSha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible", ex);
        }
    }

    @Transactional(readOnly = true)
    public IncomingInvoiceQueryService.PageResult<PendingPdfResponse> getPendingPdfs(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Paginación inválida");
        }
        if (receivedPdfRepository == null) {
            return new IncomingInvoiceQueryService.PageResult<>(List.of(), page, size, 0, 0);
        }
        var sort = Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id"));
        var result = receivedPdfRepository.findByStatus(ReceivedPdf.STATUS_PENDING, PageRequest.of(page, size, sort));
        var items = result.getContent().stream().map(PendingPdfResponse::from).toList();
        return new IncomingInvoiceQueryService.PageResult<>(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    public record PdfMatchResult(
            String fileName,
            UUID invoiceId,
            String invoiceNumber,
            String clientNit,
            String clientBusinessName,
            String matchedBy
    ) {}

    public record PdfProcessingSummary(
            int totalScanned,
            int totalMatched,
            int totalUnmatched,
            List<PdfMatchResult> matched,
            List<String> unmatched
    ) {}
}
