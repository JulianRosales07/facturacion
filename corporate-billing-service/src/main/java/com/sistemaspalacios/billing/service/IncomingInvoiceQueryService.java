package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncomingInvoiceQueryService {
    private final RepositorioFactura invoices;
    private final com.sistemaspalacios.billing.repository.ReceivedPdfRepository receivedPdfRepository;
    private final com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor pdfQrCodeExtractor;
    private final com.sistemaspalacios.billing.repository.RepositorioPago paymentRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public IncomingInvoiceQueryService(
            @Qualifier("repositorioFactura") RepositorioFactura invoices,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.sistemaspalacios.billing.repository.ReceivedPdfRepository receivedPdfRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.sistemaspalacios.billing.service.siigo.PdfQrCodeExtractor pdfQrCodeExtractor,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.sistemaspalacios.billing.repository.RepositorioPago paymentRepository
    ) {
        this.invoices = invoices;
        this.receivedPdfRepository = receivedPdfRepository;
        this.pdfQrCodeExtractor = pdfQrCodeExtractor;
        this.paymentRepository = paymentRepository;
    }

    public IncomingInvoiceQueryService(@Qualifier("repositorioFactura") RepositorioFactura invoices) {
        this(invoices, null, null, null);
    }

    public PageResult<InvoiceRow> search(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, String paymentStatus, Boolean includePaid, int page, int size) {
        return search(search, number, type, period, from, to, pdfAvailable, paymentStatus, includePaid, null, page, size);
    }

    /**
     * @param origin {@code SIIGO} (documentos recibidos, por omisión), {@code INTERNO} (facturas
     *               generadas aquí, en preparación) o {@code TODAS}.
     */
    public PageResult<InvoiceRow> search(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, String paymentStatus, Boolean includePaid,
            String origin, int page, int size) {
        validatePaging(page, size, from, to);
        boolean withPaid = includePaid == null || includePaid;
        Specification<Invoice> filter = buildFilter(search, number, type, period, from, to, pdfAvailable, paymentStatus, withPaid, origin);
        var result = invoices.findAll(filter, PageRequest.of(page, size, DEFAULT_SORT));
        return new PageResult<>(result.getContent().stream().map(this::toRow).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public PageResult<InvoiceRow> search(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, int page, int size) {
        return search(search, number, type, period, from, to, pdfAvailable, null, true, page, size);
    }

    /**
     * Misma consulta que {@link #search}, pero consolidada: un único objeto por NIT con todas sus
     * facturas, detallando su {@code estado_pago}, el {@code valor_total_servicio}, {@code valor_pagado} y {@code valor_pendiente}.
     */
    public PageResult<ClientInvoiceGroup> searchGroupedByNit(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, String paymentStatus, Boolean includePaid, int page, int size) {
        return searchGroupedByNit(search, number, type, period, from, to, pdfAvailable, paymentStatus, includePaid, null, page, size);
    }

    /** @param origin igual que en {@link #search}: SIIGO por omisión, INTERNO o TODAS. */
    public PageResult<ClientInvoiceGroup> searchGroupedByNit(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, String paymentStatus, Boolean includePaid,
            String origin, int page, int size) {
        validatePaging(page, size, from, to);
        boolean withPaid = includePaid != null && includePaid;
        Specification<Invoice> filter = buildFilter(search, number, type, period, from, to, pdfAvailable, paymentStatus, withPaid, origin);
        List<InvoiceRow> rows = invoices.findAll(filter, DEFAULT_SORT).stream().map(this::toRow).toList();
        List<ClientInvoiceGroup> groups = groupByNit(rows);
        int totalPages = (int) Math.ceil(groups.size() / (double) size);
        int fromIndex = Math.min(page * size, groups.size());
        int toIndex = Math.min(fromIndex + size, groups.size());
        return new PageResult<>(List.copyOf(groups.subList(fromIndex, toIndex)), page, size, groups.size(), totalPages);
    }

    public PageResult<ClientInvoiceGroup> searchGroupedByNit(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, Boolean includePaid, int page, int size) {
        return searchGroupedByNit(search, number, type, period, from, to, pdfAvailable, null, includePaid, page, size);
    }

    public PageResult<ClientInvoiceGroup> searchGroupedByNit(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, int page, int size) {
        return searchGroupedByNit(search, number, type, period, from, to, pdfAvailable, null, false, page, size);
    }

    /**
     * Busca y consolida las facturas pendientes de un cliente identificado por su NIT (portal de pago).
     * Incluye las de Siigo y las internas con número (la factura del mes con sus novedades se
     * cobra de una vez). Las facturas PAGADAS se excluyen. Si todas las facturas del NIT están
     * pagadas, retorna {@link java.util.Optional#empty()}.
     */
    public java.util.Optional<ClientInvoiceGroup> findGroupByNit(String nit) {
        if (nit == null || nit.isBlank()) return java.util.Optional.empty();
        String cleanNit = nit.trim().replaceAll("\\s+", "");
        Specification<Invoice> filter = buildFilter(cleanNit, null, null, null, null, null, null, null, false, "TODAS");
        // Una interna ya pagada en la pasarela no se vuelve a cobrar cuando Siigo devuelve su
        // documento oficial (mismo tipo y número) y la cartera aún no refleja el pago.
        Specification<Invoice> paidInternal = buildFilter(cleanNit, null, null, null, null, null, null, "PAGADA", true, "INTERNO");
        java.util.Set<String> paidKeys = invoices.findAll(paidInternal).stream()
                .map(i -> documentKey(i.getBillingType(), i.getInvoiceNumber()))
                .collect(java.util.stream.Collectors.toSet());
        List<InvoiceRow> rows = invoices.findAll(filter, DEFAULT_SORT).stream().map(this::toRow)
                .filter(r -> !paidKeys.contains(documentKey(r.billingType(), r.invoiceNumber())))
                .toList();
        List<ClientInvoiceGroup> groups = groupByNit(rows);
        return groups.stream().filter(g -> g.nit().equalsIgnoreCase(cleanNit) && g.invoiceCount() > 0).findFirst();
    }

    /**
     * Agrupa facturas por NIT conservando el orden de llegada.
     * <ul>
     *   <li>Clave de agrupación: el NIT sin espacios. NIT distintos nunca se suman entre sí.</li>
     *   <li>Clave de factura: {@code tipo + ":" + consecutivo} (sin ceros a la izquierda).</li>
     *   <li>Calcula: {@code valor_total_servicio}, {@code valor_pagado} y {@code valor_pendiente}.</li>
     * </ul>
     */
    public static List<ClientInvoiceGroup> groupByNit(List<InvoiceRow> rows) {
        Map<String, GroupAccumulator> byNit = new LinkedHashMap<>();
        for (InvoiceRow row : rows) {
            if (row == null || row.clientNit() == null || row.clientNit().isBlank()) continue;
            String nit = row.clientNit().replaceAll("\\s+", "");
            GroupAccumulator group = byNit.computeIfAbsent(nit, GroupAccumulator::new);
            group.add(row);
        }
        return byNit.values().stream()
                .map(GroupAccumulator::toGroup)
                .filter(g -> g.invoiceCount() > 0)
                .toList();
    }

    public static String normalizedNumber(String number) {
        if (number == null) return "";
        var matcher = java.util.regex.Pattern.compile("(\\d+)$").matcher(number.trim());
        return matcher.find() ? matcher.group(1).replaceFirst("^0+(?!$)", "") : number.trim();
    }

    static String documentKey(BillingType type, String number) {
        return (type == null ? "?" : type.name()) + ":" + normalizedNumber(number);
    }

    private static final class GroupAccumulator {
        private final String nit;
        private String businessName;
        private final Map<String, GroupedInvoice> invoicesByKey = new LinkedHashMap<>();
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal paidTotal = BigDecimal.ZERO;
        private BigDecimal pendingTotal = BigDecimal.ZERO;

        GroupAccumulator(String nit) { this.nit = nit; }

        void add(InvoiceRow row) {
            if ((businessName == null || businessName.isBlank()) && row.businessName() != null && !row.businessName().isBlank()) {
                businessName = row.businessName();
            }
            String key = documentKey(row.billingType(), row.invoiceNumber());
            GroupedInvoice existing = invoicesByKey.get(key);
            if (existing != null) {
                // Misma factura dos veces: no se suma otra vez. Si una es la interna y la otra el
                // documento oficial que Siigo emitió a partir de ella, manda el de Siigo.
                boolean officialReplacesInternal = "INTERNO".equals(existing.origen()) && !"INTERNO".equals(row.origen());
                if (!officialReplacesInternal) return;
                invoicesByKey.remove(key);
                total = total.subtract(existing.serviceValue());
                paidTotal = paidTotal.subtract(existing.serviceValue().subtract(existing.pendingBalance()).max(BigDecimal.ZERO));
                pendingTotal = pendingTotal.subtract(existing.pendingBalance());
            }
            BigDecimal value = (row.total() == null ? BigDecimal.ZERO : row.total()).setScale(2, RoundingMode.HALF_UP);

            QrDto qr = null;
            if (row.qrContent() != null && !row.qrContent().isBlank()) {
                String consecutivo = normalizedNumber(row.invoiceNumber());
                String tipo = row.billingType() != null ? row.billingType().name() : "F1";
                String imgUrl = "/api/v1/facturas/" + tipo + "-" + consecutivo + "/qr.png";
                qr = QrDto.of(row.qrContent(), imgUrl);
            } else if (row.pdfAvailable()) {
                qr = QrDto.notFound();
            }

            String estadoPago = row.estadoPago() != null ? row.estadoPago() : "PENDIENTE";
            invoicesByKey.put(key, new GroupedInvoice(row.id(), row.billingType(), row.invoiceNumber(), key,
                    value, row.issueDate(), row.dueDate(), row.pdfAvailable(), row.cufe(), qr,
                    estadoPago, row.fechaPago(), row.referenciaPago(), row.transactionId(),
                    row.origen() != null ? row.origen() : "SIIGO", row.pendingBalance(), row.officialDetailConfirmed(), row.creditBalance()));

            total = total.add(value);
            paidTotal = paidTotal.add(value.subtract(row.pendingBalance()).max(BigDecimal.ZERO));
            pendingTotal = pendingTotal.add(row.pendingBalance());
        }

        ClientInvoiceGroup toGroup() {
            List<GroupedInvoice> list = List.copyOf(invoicesByKey.values());
            return new ClientInvoiceGroup(
                    nit,
                    businessName,
                    list.size(),
                    list,
                    total.setScale(2, RoundingMode.HALF_UP),
                    paidTotal.setScale(2, RoundingMode.HALF_UP),
                    pendingTotal.setScale(2, RoundingMode.HALF_UP)
            );
        }
    }

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("issueDate"), Sort.Order.desc("id"));

    private static void validatePaging(int page, int size, LocalDate from, LocalDate to) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Página inválida; tamaño permitido: 1 a 100");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("El rango de fechas es inválido");
    }

    private InvoiceRow toRow(Invoice i) {
        String qrContent = i.getQrContent();
        String qrImagePath = i.getQrImagePath();

        // Lazy-extract QR si la factura tiene PDF en disco pero aún no tiene QR persistido
        if ((qrContent == null || qrContent.isBlank()) && i.getPdfPath() != null && !i.getPdfPath().isBlank() && pdfQrCodeExtractor != null) {
            try {
                java.io.File pdfFile = new java.io.File(i.getPdfPath());
                if (pdfFile.exists() && pdfFile.isFile()) {
                    var extracted = pdfQrCodeExtractor.extractQr(pdfFile);
                    if (extracted.hasContent()) {
                        qrContent = extracted.content();
                        i.setQrContent(qrContent);
                        if (extracted.imageBytes() != null && extracted.imageBytes().length > 0) {
                            java.nio.file.Path parentDir = pdfFile.toPath().getParent();
                            if (parentDir != null) {
                                java.nio.file.Path qrFile = parentDir.resolve("qr.png");
                                java.nio.file.Files.write(qrFile, extracted.imageBytes());
                                qrImagePath = qrFile.toString();
                                i.setQrImagePath(qrImagePath);
                            }
                        }
                        try {
                            invoices.save(i);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ex) {
                // Silencioso para no romper consultas
            }
        }

        String estadoPago = i.hasSiigoBalance() && i.getSiigoBalance().isNegative() ? "SALDO_A_FAVOR" : i.getStatus() == InvoiceStatus.PAID ? "PAGADA" :
                (i.getStatus() == InvoiceStatus.VOIDED ? "ANULADA" : "PENDIENTE");
        LocalDate fechaPago = null;
        String referenciaPago = null;
        String transactionId = null;
        BigDecimal valorPagado = null;
        String proveedorPago = null;

        if (i.getStatus() == InvoiceStatus.PAID && paymentRepository != null) {
            List<com.sistemaspalacios.billing.domain.model.Payment> payments = paymentRepository.findAppliedPaymentsByInvoiceId(i.getId());
            if (!payments.isEmpty()) {
                var p = payments.get(0);
                fechaPago = p.getPaymentDate();
                referenciaPago = p.getTransactionNumber();
                transactionId = p.getTransactionNumber();
                // Valor cruzado a ESTA factura (neto recaudado + retenciones válidas), no el neto
                // del pago completo, que puede cubrir varias facturas.
                valorPagado = p.getInvoiceCrossings().stream()
                        .filter(c -> c.getInvoiceId().equals(i.getId()))
                        .map(c -> c.getAppliedAmount().amount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                proveedorPago = p.getPaymentMethod();
            }
        }

        return new InvoiceRow(
                i.getId(),
                i.getInvoiceNumber(),
                i.getClientNit(),
                i.getClientBusinessName(),
                i.getBillingType(),
                i.getIssueDate(),
                i.getDueDate(),
                i.getTotalAmount().amount(),
                i.getPdfPath() != null && !i.getPdfPath().isBlank(),
                i.getCufe(),
                qrContent,
                qrImagePath,
                estadoPago,
                estadoPago,
                fechaPago,
                referenciaPago,
                transactionId,
                valorPagado,
                proveedorPago,
                i.getDocumentOrigin(),
                i.getPendingBalance().amount(),
                !i.hasCarteraPlaceholderDetail(),
                i.hasSiigoBalance() && i.getSiigoBalance().isNegative() ? i.getSiigoBalance().amount().negate() : BigDecimal.ZERO
        );
    }

    private Specification<Invoice> buildFilter(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable) {
        return buildFilter(search, number, type, period, from, to, pdfAvailable, null, false);
    }

    private Specification<Invoice> buildFilter(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, boolean includePaid) {
        return buildFilter(search, number, type, period, from, to, pdfAvailable, null, includePaid);
    }

    private Specification<Invoice> buildFilter(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, String paymentStatus, boolean includePaid) {
        return buildFilter(search, number, type, period, from, to, pdfAvailable, paymentStatus, includePaid, null);
    }

    /**
     * Origen de documentos a listar. Por omisión solo SIIGO: el portal de pagos y el historial
     * nunca deben ofrecer una factura interna que todavía está en preparación.
     */
    static String normalizedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return "SIIGO";
        String o = origin.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (o) {
            case "INTERNO", "INTERNAS", "INTERNA" -> "INTERNO";
            case "TODAS", "TODOS", "ALL" -> "TODAS";
            default -> "SIIGO";
        };
    }

    private Specification<Invoice> buildFilter(String search, String number, BillingType type, String period,
            LocalDate from, LocalDate to, Boolean pdfAvailable, String paymentStatus, boolean includePaid, String origin) {
        String effectiveOrigin = normalizedOrigin(origin);
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!"TODAS".equals(effectiveOrigin)) {
                predicates.add(cb.equal(root.get("documentOrigin"), effectiveOrigin));
            }
            if (!"SIIGO".equals(effectiveOrigin)) {
                // Una interna en BORRADOR o sin número no es una factura que se pueda mostrar ni cobrar.
                predicates.add(cb.not(cb.and(cb.equal(root.get("documentOrigin"), "INTERNO"),
                        cb.or(cb.equal(root.get("status"), InvoiceStatus.DRAFT), cb.isNull(root.get("invoiceNumber"))))));
            }

            if (paymentStatus != null && !paymentStatus.isBlank()) {
                String ps = paymentStatus.trim().toUpperCase(java.util.Locale.ROOT);
                if ("PAGADA".equals(ps) || "PAID".equals(ps)) {
                    predicates.add(cb.equal(root.get("status"), InvoiceStatus.PAID));
                } else if ("PENDIENTE".equals(ps) || "PENDING".equals(ps)) {
                    predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.PAID));
                    predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.VOIDED));
                    predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.SUPERSEDED));
                } else if ("ANULADA".equals(ps) || "VOIDED".equals(ps)) {
                    predicates.add(cb.equal(root.get("status"), InvoiceStatus.VOIDED));
                } else if ("EN_PROCESO".equals(ps)) {
                    predicates.add(cb.equal(root.get("status"), InvoiceStatus.DRAFT));
                }
            } else if (!includePaid) {
                // Control individual de facturas pagadas:
                // Solo facturas pendientes (excluye PAGADAS, ANULADAS y REEMPLAZADAS)
                predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.PAID));
                predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.VOIDED));
                predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.SUPERSEDED));
            }

            if (search != null && !search.isBlank()) predicates.add(cb.or(
                    cb.like(cb.lower(root.get("clientNit")), pattern(search), '\\'),
                    cb.like(cb.lower(root.get("clientBusinessName")), pattern(search), '\\')));
            if (number != null && !number.isBlank()) predicates.add(cb.like(root.get("invoiceNumber"), pattern(number), '\\'));
            if (type != null) predicates.add(cb.equal(root.get("billingType"), type));
            if (period != null && !period.isBlank()) {
                YearMonth month = YearMonth.parse(period);
                predicates.add(cb.between(root.get("issueDate"), month.atDay(1), month.atEndOfMonth()));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("issueDate"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("issueDate"), to));
            if (pdfAvailable != null) {
                var available = cb.and(cb.isNotNull(root.get("pdfPath")), cb.notEqual(root.get("pdfPath"), ""));
                predicates.add(pdfAvailable ? available : cb.or(cb.isNull(root.get("pdfPath")), cb.equal(root.get("pdfPath"), "")));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private String pattern(String value) {
        return "%" + value.trim().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * Consulta el historial completo de facturas de un cliente (tanto pendientes como pagadas),
     * vinculando cada factura con su respectivo pago, transacción y estado.
     */
    public List<InvoiceHistoryItem> getInvoiceHistoryByNit(String nit) {
        if (nit == null || nit.isBlank()) return List.of();
        String cleanNit = nit.trim().replaceAll("\\s+", "");
        Specification<Invoice> filter = buildFilter(cleanNit, null, null, null, null, null, null, null, true);
        List<Invoice> list = invoices.findAll(filter, DEFAULT_SORT);

        List<InvoiceHistoryItem> history = new java.util.ArrayList<>();
        for (Invoice inv : list) {
            String consecutivo = normalizedNumber(inv.getInvoiceNumber());
            String tipo = inv.getBillingType() != null ? inv.getBillingType().name() : "F1";
            String clave = documentKey(inv.getBillingType(), inv.getInvoiceNumber());
            BigDecimal valor = inv.getTotalAmount() != null ? inv.getTotalAmount().amount() : BigDecimal.ZERO;
            String estado = inv.getStatus() == InvoiceStatus.PAID ? "PAGADA" : "PENDIENTE";

            LocalDate fechaPago = null;
            String referenciaPago = null;
            String txId = null;
            String proveedor = null;
            UUID pagoId = null;

            if (inv.getStatus() == InvoiceStatus.PAID && paymentRepository != null) {
                List<com.sistemaspalacios.billing.domain.model.Payment> payments = paymentRepository.findAppliedPaymentsByInvoiceId(inv.getId());
                if (!payments.isEmpty()) {
                    var p = payments.get(0);
                    fechaPago = p.getPaymentDate();
                    referenciaPago = p.getTransactionNumber();
                    txId = p.getTransactionNumber();
                    proveedor = p.getPaymentMethod();
                    pagoId = p.getId();
                }
            }

            history.add(new InvoiceHistoryItem(
                    inv.getClientNit(),
                    inv.getClientBusinessName(),
                    clave,
                    tipo,
                    consecutivo,
                    valor,
                    estado,
                    inv.getIssueDate(),
                    fechaPago,
                    referenciaPago,
                    txId,
                    proveedor,
                    inv.getCufe(),
                    inv.getId(),
                    pagoId
            ));
        }
        return history;
    }

    public Summary summary() {
        long total = invoices.countByDocumentOrigin("SIIGO");
        long withPdf = invoices.countReceivedWithPdf();
        long pendingReceivedPdfs = receivedPdfRepository != null
                ? receivedPdfRepository.countByStatus(com.sistemaspalacios.billing.domain.model.ReceivedPdf.STATUS_PENDING)
                : 0L;
        return new Summary(total, withPdf, total - withPdf, pendingReceivedPdfs);
    }

    public record InvoiceRow(
            UUID id,
            @JsonProperty("invoiceNumber") String invoiceNumber,
            @JsonProperty("clientNit") String clientNit,
            @JsonProperty("businessName") String businessName,
            @JsonProperty("billingType") BillingType billingType,
            @JsonProperty("issueDate") LocalDate issueDate,
            @JsonProperty("dueDate") LocalDate dueDate,
            @JsonProperty("total") BigDecimal total,
            @JsonProperty("pdfAvailable") boolean pdfAvailable,
            @JsonProperty("cufe") String cufe,
            @JsonProperty("qrContent") String qrContent,
            @JsonProperty("qrImagePath") String qrImagePath,
            @JsonProperty("estado") String estado,
            @JsonProperty("estado_pago") String estadoPago,
            @JsonProperty("fecha_pago") LocalDate fechaPago,
            @JsonProperty("referencia_pago") String referenciaPago,
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("valor_pagado") BigDecimal valorPagado,
            @JsonProperty("proveedor_pago") String proveedorPago,
            @JsonProperty("origen") String origen,
            @JsonProperty("saldo_pendiente") BigDecimal pendingBalance,
            @JsonProperty("detalle_oficial_confirmado") boolean officialDetailConfirmed,
            @JsonProperty("saldo_a_favor") BigDecimal creditBalance) {

        public InvoiceRow(UUID id, String invoiceNumber, String clientNit, String businessName, BillingType billingType,
                LocalDate issueDate, LocalDate dueDate, BigDecimal total, boolean pdfAvailable, String cufe,
                String qrContent, String qrImagePath, String estado, String estadoPago, LocalDate fechaPago,
                String referenciaPago, String transactionId, BigDecimal valorPagado, String proveedorPago, String origen) {
            this(id, invoiceNumber, clientNit, businessName, billingType, issueDate, dueDate, total, pdfAvailable, cufe,
                    qrContent, qrImagePath, estado, estadoPago, fechaPago, referenciaPago, transactionId, valorPagado,
                    proveedorPago, origen, "PAGADA".equals(estadoPago) || "ANULADA".equals(estadoPago) ? BigDecimal.ZERO : total, true, BigDecimal.ZERO);
        }

        public InvoiceRow(UUID id, String invoiceNumber, String clientNit, String businessName, BillingType billingType,
                LocalDate issueDate, LocalDate dueDate, BigDecimal total, boolean pdfAvailable, String cufe,
                String qrContent, String qrImagePath, String estado, String estadoPago, LocalDate fechaPago,
                String referenciaPago, String transactionId, BigDecimal valorPagado, String proveedorPago) {
            this(id, invoiceNumber, clientNit, businessName, billingType, issueDate, dueDate, total, pdfAvailable, cufe,
                    qrContent, qrImagePath, estado, estadoPago, fechaPago, referenciaPago, transactionId,
                    valorPagado, proveedorPago, "SIIGO");
        }

        // Alias para compatibilidad con vistas y DTOs cliente
        @JsonProperty("consecutivo")
        public String consecutivo() { return invoiceNumber; }

        @JsonProperty("nit")
        public String nit() { return clientNit; }

        @JsonProperty("razon_social")
        public String razonSocial() { return businessName; }

        @JsonProperty("tipo")
        public String tipo() { return billingType != null ? billingType.name() : "F1"; }

        @JsonProperty("valor_total")
        public BigDecimal valorTotal() { return total; }

        public InvoiceRow(UUID id, String invoiceNumber, String clientNit, String businessName, BillingType billingType,
                LocalDate issueDate, LocalDate dueDate, BigDecimal total, boolean pdfAvailable, String cufe) {
            this(id, invoiceNumber, clientNit, businessName, billingType, issueDate, dueDate, total, pdfAvailable, cufe,
                    null, null, "PENDIENTE", "PENDIENTE", null, null, null, null, null);
        }

        public InvoiceRow(UUID id, String invoiceNumber, String clientNit, String businessName, BillingType billingType,
                LocalDate issueDate, LocalDate dueDate, BigDecimal total, boolean pdfAvailable, String cufe,
                String qrContent, String qrImagePath) {
            this(id, invoiceNumber, clientNit, businessName, billingType, issueDate, dueDate, total, pdfAvailable, cufe,
                    qrContent, qrImagePath, "PENDIENTE", "PENDIENTE", null, null, null, null, null);
        }

        public InvoiceRow(UUID id, String invoiceNumber, String clientNit, String businessName, BillingType billingType,
                LocalDate issueDate, LocalDate dueDate, BigDecimal total, boolean pdfAvailable, String cufe,
                String qrContent, String qrImagePath, String estado) {
            this(id, invoiceNumber, clientNit, businessName, billingType, issueDate, dueDate, total, pdfAvailable, cufe,
                    qrContent, qrImagePath, estado, estado, null, null, null, null, null);
        }
    }

    public record InvoiceHistoryItem(
            String nit,
            String clientBusinessName,
            String facturaClave,
            String tipo,
            String consecutivo,
            BigDecimal valor,
            String estado,
            LocalDate fechaEmision,
            LocalDate fechaPago,
            String referenciaPago,
            String transactionId,
            String proveedorMedioPago,
            String cufe,
            UUID facturaId,
            UUID pagoId
    ) {}

    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}

    /** Un objeto por NIT. {@code valor_total_servicio} = suma de todas las facturas únicas, discriminando pagado y pendiente. */
    public record ClientInvoiceGroup(
            @JsonProperty("nit") String nit,
            @JsonProperty("razon_social") String businessName,
            @JsonProperty("cantidad_facturas") int invoiceCount,
            @JsonProperty("facturas") List<GroupedInvoice> invoices,
            @JsonProperty("valor_total_servicio") BigDecimal totalServiceValue,
            @JsonProperty("valor_pagado") BigDecimal totalPaidValue,
            @JsonProperty("valor_pendiente") BigDecimal totalPendingValue) {

        public ClientInvoiceGroup(String nit, String businessName, int invoiceCount, List<GroupedInvoice> invoices, BigDecimal totalServiceValue) {
            this(nit, businessName, invoiceCount, invoices, totalServiceValue, BigDecimal.ZERO, totalServiceValue);
        }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record QrDto(
            @JsonProperty("contenido") String content,
            @JsonProperty("imagen") String image,
            @JsonProperty("estado") String status) {
        public static QrDto of(String content, String image) {
            return new QrDto(content, image, null);
        }
        public static QrDto notFound() {
            return new QrDto(null, null, "NO_ENCONTRADO");
        }
    }

    public record GroupedInvoice(
            @JsonProperty("id") UUID id,
            @JsonProperty("tipo") BillingType billingType,
            @JsonProperty("consecutivo") String invoiceNumber,
            @JsonProperty("clave_documento") String documentKey,
            @JsonProperty("valor_servicio") BigDecimal serviceValue,
            @JsonProperty("fecha_emision") LocalDate issueDate,
            @JsonProperty("fecha_vencimiento") LocalDate dueDate,
            @JsonProperty("pdf_disponible") boolean pdfAvailable,
            @JsonProperty("cufe") String cufe,
            @JsonProperty("qr") QrDto qr,
            @JsonProperty("estado_pago") String estadoPago,
            @JsonProperty("fecha_pago") LocalDate fechaPago,
            @JsonProperty("referencia_pago") String referenciaPago,
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("origen") String origen,
            @JsonProperty("saldo_pendiente") BigDecimal pendingBalance,
            @JsonProperty("detalle_oficial_confirmado") boolean officialDetailConfirmed,
            @JsonProperty("saldo_a_favor") BigDecimal creditBalance) {

        public GroupedInvoice(UUID id, BillingType billingType, String invoiceNumber, String documentKey,
                BigDecimal serviceValue, LocalDate issueDate, LocalDate dueDate, boolean pdfAvailable,
                String cufe, QrDto qr, String estadoPago, LocalDate fechaPago, String referenciaPago,
                String transactionId, String origen) {
            this(id, billingType, invoiceNumber, documentKey, serviceValue, issueDate, dueDate, pdfAvailable,
                    cufe, qr, estadoPago, fechaPago, referenciaPago, transactionId, origen,
                    "PAGADA".equals(estadoPago) || "ANULADA".equals(estadoPago) ? BigDecimal.ZERO : serviceValue, true, BigDecimal.ZERO);
        }

        public GroupedInvoice(UUID id, BillingType billingType, String invoiceNumber, String documentKey,
                BigDecimal serviceValue, LocalDate issueDate, LocalDate dueDate, boolean pdfAvailable,
                String cufe, QrDto qr, String estadoPago, LocalDate fechaPago, String referenciaPago,
                String transactionId) {
            this(id, billingType, invoiceNumber, documentKey, serviceValue, issueDate, dueDate, pdfAvailable,
                    cufe, qr, estadoPago, fechaPago, referenciaPago, transactionId, "SIIGO");
        }

        public GroupedInvoice(UUID id, BillingType billingType, String invoiceNumber, String documentKey,
                BigDecimal serviceValue, LocalDate issueDate, LocalDate dueDate, boolean pdfAvailable,
                String cufe, QrDto qr) {
            this(id, billingType, invoiceNumber, documentKey, serviceValue, issueDate, dueDate, pdfAvailable,
                    cufe, qr, "PENDIENTE", null, null, null);
        }
    }

    public record Summary(long totalInvoices, long withPdf, long pendingPdf, long pendingReceivedPdfs) {
        public Summary(long totalInvoices, long withPdf, long pendingPdf) {
            this(totalInvoices, withPdf, pendingPdf, 0L);
        }
    }
}

