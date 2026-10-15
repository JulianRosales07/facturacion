package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.Payment;
import com.sistemaspalacios.billing.domain.model.PaymentInvoice;
import com.sistemaspalacios.billing.domain.model.PaymentWithholding;
import com.sistemaspalacios.billing.domain.model.RetentionAuditEvent;
import com.sistemaspalacios.billing.domain.model.SourceChannel;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioEventoRetencion;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPago;
import com.sistemaspalacios.billing.repository.RepositorioRetencionPago;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Consulta de la auditoría de retenciones. Todo sale del snapshot y del historial guardados; nada
 * se reconstruye desde el catálogo tributario actual. Las horas se guardan en UTC y se entregan en
 * la zona del negocio (America/Bogota, -05:00).
 */
@Service
public class WithholdingAuditQueryService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_EXPORT_ROWS = 50_000;

    public record Actor(String type, UUID userId, String username, String name, String nit, String sessionId, String label) {}

    public record AuditRow(
            UUID id, UUID paymentId, String paymentStatus, String clientNit, String clientName,
            UUID invoiceId, List<String> invoiceNumbers,
            UUID conceptValidityId, String conceptCode, String conceptName, int conceptVersion,
            LocalDate validFrom, LocalDate validTo,
            BigDecimal enteredPercentage, BigDecimal appliedPercentage, BigDecimal maxPercentage,
            Money calculationBase, Money minimumBase, Money withheldAmount,
            boolean applicable, String nonApplicableReason, String adjustmentReason,
            String selectionStatus, OffsetDateTime selectedAt, OffsetDateTime appliedAt,
            String sourceChannel, Actor selectedBy, String ipAddress, String userAgent
    ) {}

    public record AuditEventRow(
            UUID id, String eventType, String sessionId, UUID paymentId, UUID invoiceId, String conceptCode, String conceptName,
            BigDecimal oldPercentage, BigDecimal newPercentage, OffsetDateTime occurredAt, String sourceChannel, Actor actor,
            String ipAddress, String userAgent
    ) {}

    public record PaymentAudit(
            UUID paymentId, String status, String applicationMode, String clientNit, String clientName,
            List<String> invoiceNumbers, OffsetDateTime createdAt, OffsetDateTime confirmedAt,
            List<AuditRow> audit, List<AuditEventRow> events
    ) {}

    public record AuditFilter(String nit, String invoice, UUID paymentId, String concept, String user, SourceChannel channel,
                              LocalDate from, LocalDate to) {}

    public record AuditPage(List<AuditRow> items, int page, int size, long totalItems, int totalPages) {}

    private final RepositorioRetencionPago withholdings;
    private final RepositorioEventoRetencion events;
    private final RepositorioPago payments;
    private final RepositorioCliente clients;
    private final RepositorioFactura invoices;

    public WithholdingAuditQueryService(RepositorioRetencionPago withholdings, RepositorioEventoRetencion events,
                                        RepositorioPago payments, RepositorioCliente clients, RepositorioFactura repositorioFactura) {
        this.withholdings = withholdings;
        this.events = events;
        this.payments = payments;
        this.clients = clients;
        this.invoices = repositorioFactura;
    }

    /** Auditoría completa de un pago: snapshot final + historial de interacción. */
    @Transactional(readOnly = true)
    public PaymentAudit forPayment(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new NoSuchElementException("Pago no encontrado: " + paymentId));
        List<PaymentWithholding> rows = withholdings.findByPaymentId(paymentId);
        Context context = context(rows);
        Set<String> sessions = rows.stream().map(PaymentWithholding::getSelectionSessionId).filter(s -> s != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // IN () vacío no es SQL válido: un valor que nunca existe como sesión.
        List<RetentionAuditEvent> history = events.findForPayment(paymentId, sessions.isEmpty() ? List.of("-") : sessions);
        CorporateClient client = clients.findById(payment.getClientId()).orElse(null);
        return new PaymentAudit(payment.getId(), payment.getStatus(),
                payment.getApplicationMode() != null ? payment.getApplicationMode().name() : null,
                client != null ? client.getNit() : null, client != null ? client.getBusinessName() : null,
                context.invoiceNumbers(payment), local(payment.getCreatedAt()), local(payment.getConfirmedAt()),
                rows.stream().map(w -> row(w, context)).toList(),
                history.stream().map(WithholdingAuditQueryService::eventRow).toList());
    }

    @Transactional(readOnly = true)
    public AuditPage search(AuditFilter filter, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<PaymentWithholding> result = withholdings.findAll(specification(filter),
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "selectedAt")));
        Context context = context(result.getContent());
        return new AuditPage(result.getContent().stream().map(w -> row(w, context)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Evidencia en Excel (.xlsx): hoja Retenciones (snapshot), Historial (eventos de esos pagos) e
     * Información (filtros, fecha y usuario que exportó). Mismos filtros y límite que el CSV.
     */
    @Transactional(readOnly = true)
    public byte[] exportXlsx(AuditFilter filter, String generatedBy) {
        List<PaymentWithholding> found = withholdings.findAll(specification(filter),
                PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "selectedAt"))).getContent();
        Context context = context(found);
        List<AuditRow> rows = found.stream().map(w -> row(w, context)).toList();

        List<UUID> paymentIds = found.stream().map(w -> w.getPayment().getId()).distinct().toList();
        List<String> sessions = found.stream().map(PaymentWithholding::getSelectionSessionId).filter(x -> x != null).distinct().toList();
        java.util.Map<UUID, RetentionAuditEvent> history = new java.util.LinkedHashMap<>();
        // Por bloques: PostgreSQL limita la cantidad de parámetros de una consulta.
        for (int i = 0; i < Math.max(paymentIds.size(), sessions.size()); i += 1000) {
            List<UUID> ids = paymentIds.subList(Math.min(i, paymentIds.size()), Math.min(i + 1000, paymentIds.size()));
            List<String> sess = sessions.subList(Math.min(i, sessions.size()), Math.min(i + 1000, sessions.size()));
            events.findForPayments(ids.isEmpty() ? List.of(new UUID(0, 0)) : ids, sess.isEmpty() ? List.of("-") : sess)
                    .forEach(e -> history.put(e.getId(), e));
        }
        List<AuditEventRow> eventRows = history.values().stream()
                .sorted(java.util.Comparator.comparing(RetentionAuditEvent::getOccurredAt))
                .map(WithholdingAuditQueryService::eventRow).toList();

        java.time.format.DateTimeFormatter stamp = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        List<String[]> info = new ArrayList<>();
        info.add(new String[]{"Reporte", "Auditoría de retenciones"});
        info.add(new String[]{"Generado", java.time.ZonedDateTime.now(BUSINESS_ZONE).format(stamp)});
        info.add(new String[]{"Generado por", generatedBy});
        info.add(new String[]{"Zona horaria", "America/Bogota (UTC−5): todas las fechas y horas de este archivo"});
        info.add(new String[]{"Origen de los datos", "Valores guardados al diligenciar cada retención; no dependen del catálogo tributario actual"});
        info.add(new String[]{"Filtro NIT", filter.nit()});
        info.add(new String[]{"Filtro factura", filter.invoice()});
        info.add(new String[]{"Filtro pago", filter.paymentId() != null ? filter.paymentId().toString() : null});
        info.add(new String[]{"Filtro concepto", filter.concept()});
        info.add(new String[]{"Filtro usuario", filter.user()});
        info.add(new String[]{"Filtro canal", filter.channel() != null ? filter.channel().name() : null});
        info.add(new String[]{"Fecha desde", filter.from() != null ? filter.from().toString() : null});
        info.add(new String[]{"Fecha hasta", filter.to() != null ? filter.to().toString() : null});
        info.add(new String[]{"Retenciones", String.valueOf(rows.size())
                + (rows.size() == MAX_EXPORT_ROWS ? " (límite de la exportación; acote los filtros)" : "")});
        info.add(new String[]{"Eventos de historial", String.valueOf(eventRows.size())});
        return WithholdingAuditExcelWriter.write(rows, eventRows, info);
    }

    /** Evidencia en CSV (UTF-8 con BOM para que Excel respete tildes; separador ';' como en es-CO). */
    @Transactional(readOnly = true)
    public byte[] exportCsv(AuditFilter filter) {
        List<PaymentWithholding> rows = withholdings.findAll(specification(filter),
                PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "selectedAt"))).getContent();
        Context context = context(rows);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(String.join(";", "Pago", "Estado pago", "NIT", "Cliente", "Facturas", "Concepto", "Tipo", "Versión concepto",
                "Vigencia concepto", "% ingresado", "% aplicado", "% máximo", "Base", "Base mínima", "Valor retenido",
                "Estado selección", "Motivo no aplica", "Seleccionado", "Aplicado", "Canal", "Actor", "Usuario", "Sesión",
                "IP", "Agente de usuario")).append("\r\n");
        for (PaymentWithholding w : rows) {
            AuditRow r = row(w, context);
            csv.append(String.join(";", cell(r.paymentId()), cell(r.paymentStatus()), cell(r.clientNit()), cell(r.clientName()),
                    cell(String.join(", ", r.invoiceNumbers())), cell(r.conceptName()), cell(r.conceptCode()),
                    cell(r.conceptVersion()), cell(r.conceptValidityId()), cell(plain(r.enteredPercentage())),
                    cell(plain(r.appliedPercentage())), cell(plain(r.maxPercentage())), cell(r.calculationBase()),
                    cell(r.minimumBase()), cell(r.withheldAmount()), cell(r.selectionStatus()), cell(r.nonApplicableReason()),
                    cell(r.selectedAt()), cell(r.appliedAt()), cell(r.sourceChannel()), cell(r.selectedBy().label()),
                    cell(r.selectedBy().username()), cell(r.selectedBy().sessionId()), cell(r.ipAddress()), cell(r.userAgent())))
                    .append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------- mapeo ----------

    /** Datos auxiliares de una página (clientes, facturas y pagos) cargados en bloque. */
    private record Context(Map<UUID, CorporateClient> clients, Map<UUID, Invoice> invoices) {
        List<String> invoiceNumbers(Payment payment) {
            return payment.getInvoiceCrossings().stream().map(PaymentInvoice::getInvoiceId).distinct()
                    .map(this::invoiceLabel).toList();
        }

        String invoiceLabel(UUID invoiceId) {
            Invoice invoice = invoices.get(invoiceId);
            if (invoice == null) return invoiceId.toString();
            return (invoice.getBillingType() != null ? invoice.getBillingType().name() : "F1") + "-" + invoice.getInvoiceNumber().trim();
        }
    }

    private Context context(Collection<PaymentWithholding> rows) {
        Set<UUID> clientIds = rows.stream().map(w -> w.getPayment().getClientId()).collect(Collectors.toSet());
        Set<UUID> invoiceIds = new LinkedHashSet<>();
        for (PaymentWithholding w : rows) {
            if (w.getInvoiceId() != null) invoiceIds.add(w.getInvoiceId());
            w.getPayment().getInvoiceCrossings().forEach(c -> invoiceIds.add(c.getInvoiceId()));
        }
        Map<UUID, CorporateClient> clientMap = new HashMap<>();
        clients.findAllById(clientIds).forEach(c -> clientMap.put(c.getId(), c));
        Map<UUID, Invoice> invoiceMap = new HashMap<>();
        invoices.findAllById(invoiceIds).forEach(i -> invoiceMap.put(i.getId(), i));
        return new Context(clientMap, invoiceMap);
    }

    private static AuditRow row(PaymentWithholding w, Context context) {
        Payment payment = w.getPayment();
        CorporateClient client = context.clients().get(payment.getClientId());
        // Por factura: su factura. Consolidado: todas las facturas del pago (la retención no se reparte).
        List<String> numbers = w.getInvoiceId() != null ? List.of(context.invoiceLabel(w.getInvoiceId())) : context.invoiceNumbers(payment);
        return new AuditRow(w.getId(), payment.getId(), payment.getStatus(),
                client != null ? client.getNit() : w.getSelectedByNit(), client != null ? client.getBusinessName() : null,
                w.getInvoiceId(), numbers, w.getConceptValidityId(), w.getConceptCode(), w.getConceptName(), w.getVersionNumber(),
                w.getValidFrom(), w.getValidTo(), w.getEnteredPercentage(), w.getAppliedPercentage(), w.getMaxPercentage(),
                w.getCalculationBase(), w.getMinimumBase(), w.getWithheldAmount(), w.isApplicable(), w.getNonApplicableReason(),
                w.getAdjustmentReason(), w.getSelectionStatus().name(), local(w.getSelectedAt()), local(w.getAppliedAt()),
                w.getSourceChannel() != null ? w.getSourceChannel().name() : null,
                actor(w.getSelectedByUserId(), w.getSelectedByUsername(), w.getSelectedByName(), w.getSelectedByNit(),
                        w.getSelectionSessionId(), w.getSourceChannel()),
                w.getIpAddress(), w.getUserAgent());
    }

    private static AuditEventRow eventRow(RetentionAuditEvent e) {
        return new AuditEventRow(e.getId(), e.getType().name(), e.getSessionId(), e.getPaymentId(), e.getInvoiceId(),
                e.getConceptCode(), e.getConceptName(), e.getOldPercentage(), e.getNewPercentage(), local(e.getOccurredAt()),
                e.getChannel().name(), actor(e.getActorUserId(), e.getActorUsername(), e.getActorName(), e.getActorNit(),
                        e.getSessionId(), e.getChannel()), e.getIpAddress(), e.getUserAgent());
    }

    /** USER: persona del panel; CLIENT: cliente en la pasarela (sin usuario); SYSTEM / UNKNOWN. */
    private static Actor actor(UUID userId, String username, String name, String nit, String session, SourceChannel channel) {
        String type;
        String label;
        if (username != null) {
            type = "USER";
            label = username + (name != null ? " (" + name + ")" : "");
        } else if (channel == null) {
            type = "UNKNOWN";
            label = "No registrado";
        } else if (name != null) {
            type = "SYSTEM";
            label = name;
        } else if (nit != null) {
            type = "CLIENT";
            label = "NIT " + nit;
        } else {
            type = "UNKNOWN";
            label = "No identificado";
        }
        return new Actor(type, userId, username, name, nit, session, label);
    }

    private static OffsetDateTime local(Instant instant) {
        return instant == null ? null : instant.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    // ---------- filtros ----------

    private static Specification<PaymentWithholding> specification(AuditFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            var payment = root.get("payment");
            if (f.paymentId() != null) predicates.add(cb.equal(payment.get("id"), f.paymentId()));
            if (has(f.nit())) {
                Subquery<UUID> byNit = query.subquery(UUID.class);
                var client = byNit.from(CorporateClient.class);
                byNit.select(client.get("id")).where(cb.like(cb.lower(client.get("nit")), like(f.nit())));
                predicates.add(cb.or(payment.get("clientId").in(byNit), cb.like(cb.lower(root.get("selectedByNit")), like(f.nit()))));
            }
            if (has(f.invoice())) {
                // "F1-51987" o "51987": se busca por el número de la factura.
                String number = f.invoice().trim().replaceFirst("^[A-Za-z0-9]{1,4}[-:]", "");
                Subquery<UUID> invoiceIds = query.subquery(UUID.class);
                var invoice = invoiceIds.from(Invoice.class);
                invoiceIds.select(invoice.get("id")).where(cb.like(cb.lower(invoice.get("invoiceNumber")), like(number)));
                Subquery<UUID> paymentIds = query.subquery(UUID.class);
                var crossing = paymentIds.from(PaymentInvoice.class);
                paymentIds.select(crossing.get("payment").get("id")).where(crossing.get("invoiceId").in(invoiceIds));
                predicates.add(cb.or(root.get("invoiceId").in(invoiceIds), payment.get("id").in(paymentIds)));
            }
            if (has(f.concept())) {
                predicates.add(cb.or(cb.like(cb.lower(root.get("conceptCode")), like(f.concept())),
                        cb.like(cb.lower(root.get("conceptName")), like(f.concept()))));
            }
            if (has(f.user())) {
                predicates.add(cb.or(cb.like(cb.lower(root.get("selectedByUsername")), like(f.user())),
                        cb.like(cb.lower(root.get("selectedByName")), like(f.user()))));
            }
            if (f.channel() != null) predicates.add(cb.equal(root.get("sourceChannel"), f.channel()));
            if (f.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("selectedAt"), f.from().atStartOfDay(BUSINESS_ZONE).toInstant()));
            }
            if (f.to() != null) {
                predicates.add(cb.lessThan(root.get("selectedAt"), f.to().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "\\_") + "%";
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String cell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        // Evita inyección de fórmulas al abrir el CSV en Excel.
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !text.matches("-?\\d+(\\.\\d+)?")) text = "'" + text;
        return text.contains(";") || text.contains("\"") || text.contains("\n") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
