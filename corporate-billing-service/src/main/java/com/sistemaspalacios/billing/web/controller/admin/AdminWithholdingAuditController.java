package com.sistemaspalacios.billing.web.controller.admin;

import com.sistemaspalacios.billing.domain.model.SourceChannel;
import com.sistemaspalacios.billing.service.WithholdingAuditQueryService;
import com.sistemaspalacios.billing.service.WithholdingAuditQueryService.AuditFilter;
import com.sistemaspalacios.billing.service.WithholdingAuditQueryService.AuditPage;
import com.sistemaspalacios.billing.service.WithholdingAuditQueryService.PaymentAudit;
import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Auditoría de retenciones: qué porcentaje se diligenció, cuándo, desde qué canal y quién, sobre
 * qué pago o factura y cuánto se retuvo. Solo consulta (la evidencia no se edita).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminWithholdingAuditController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final WithholdingAuditQueryService audit;
    private final CurrentUserProvider currentUser;

    public AdminWithholdingAuditController(WithholdingAuditQueryService audit, CurrentUserProvider currentUser) {
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** Snapshot final de las retenciones del pago e historial de interacción que lo originó. */
    @GetMapping("/payments/{paymentId}/withholdings/audit")
    @PreAuthorize("hasAuthority('WITHHOLDINGS_AUDIT_VIEW')")
    public PaymentAudit forPayment(@PathVariable UUID paymentId) {
        return audit.forPayment(paymentId);
    }

    @GetMapping("/withholdings/audit")
    @PreAuthorize("hasAuthority('WITHHOLDINGS_AUDIT_VIEW')")
    public AuditPage search(
            @RequestParam(required = false) String nit,
            @RequestParam(required = false) String invoice,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return audit.search(filter(nit, invoice, paymentId, concept, user, channel, from, to), page, size);
    }

    /** Exporta la evidencia con los mismos filtros: Excel (.xlsx, por defecto) o CSV. */
    @GetMapping("/withholdings/audit/export")
    @PreAuthorize("hasAuthority('WITHHOLDINGS_AUDIT_VIEW')")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) String nit,
            @RequestParam(required = false) String invoice,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuditFilter filter = filter(nit, invoice, paymentId, concept, user, channel, from, to);
        String name = "auditoria-retenciones-" + LocalDate.now(WithholdingAuditQueryService.BUSINESS_ZONE);
        return switch (format.trim().toLowerCase(Locale.ROOT)) {
            case "xlsx", "excel" -> file(audit.exportXlsx(filter, currentUser.getCurrentUsername()), XLSX, name + ".xlsx");
            case "csv" -> file(audit.exportCsv(filter), new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8), name + ".csv");
            default -> throw new IllegalArgumentException("Formato de exportación no soportado: " + format + " (disponibles: xlsx, csv)");
        };
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private static AuditFilter filter(String nit, String invoice, UUID paymentId, String concept, String user, String channel,
                                      LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("La fecha hasta no puede ser anterior a la fecha desde");
        }
        SourceChannel parsed = null;
        if (channel != null && !channel.isBlank()) {
            try {
                parsed = SourceChannel.valueOf(channel.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Canal no válido: " + channel);
            }
        }
        return new AuditFilter(nit, invoice, paymentId, concept, user, parsed, from, to);
    }
}
