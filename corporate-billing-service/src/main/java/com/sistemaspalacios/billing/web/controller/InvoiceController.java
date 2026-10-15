package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.service.BillingService;
import com.sistemaspalacios.billing.web.dto.FacturaDetalleDto;
import com.sistemaspalacios.billing.web.dto.FacturaResumenDto;
import com.sistemaspalacios.billing.web.dto.FacturaVersionDto;
import com.sistemaspalacios.billing.web.dto.GenerarFacturaRequest;
import com.sistemaspalacios.billing.web.dto.GenerarFacturasResponse;
import com.sistemaspalacios.billing.web.dto.RegenerarFacturaRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sistemaspalacios.billing.service.InvoicePdfStorageException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * REST controller for corporate invoice operations (Sprint 4, Sprint 8).
 * Handles endpoints /api/v1/facturas and /facturas for maximum interoperability.
 */
@RestController
@RequestMapping({"/api/v1/facturas", "/facturas"})
public class InvoiceController {

    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);

    private final BillingService billingService;
    private final com.sistemaspalacios.billing.service.CreditNoteService creditNoteService;
    private final com.sistemaspalacios.billing.service.PaymentService paymentService;

    @org.springframework.beans.factory.annotation.Autowired
    public InvoiceController(
            BillingService billingService,
            com.sistemaspalacios.billing.service.CreditNoteService creditNoteService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.sistemaspalacios.billing.service.PaymentService paymentService
    ) {
        this.billingService = Objects.requireNonNull(billingService, "billingService cannot be null");
        this.creditNoteService = Objects.requireNonNull(creditNoteService, "creditNoteService cannot be null");
        this.paymentService = paymentService;
    }

    public InvoiceController(
            BillingService billingService,
            com.sistemaspalacios.billing.service.CreditNoteService creditNoteService
    ) {
        this(billingService, creditNoteService, null);
    }

    /**
     * Generar facturas consolidadas por periodo, tipo y opcionalmente NIT.
     * HU-06, HU-07, Sprint 4, Sprint 8.
     */
    @PostMapping("/generar")
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    public ResponseEntity<GenerarFacturasResponse> generateInvoices(@Valid @RequestBody GenerarFacturaRequest request) {
        List<Invoice> generated = billingService.generateConsolidated(
                request.periodoCodigo(),
                request.resolvedTipoFacturacion(),
                request.resolvedTipoCorte(),
                request.nit()
        );

        List<FacturaResumenDto> resumenes = generated.stream()
                .map(inv -> FacturaResumenDto.fromDomain(inv, request.periodoCodigo()))
                .toList();

        return ResponseEntity.status(HttpStatus.CREATED).body(GenerarFacturasResponse.of(resumenes));
    }

    /**
     * Consultar facturas con filtros avanzados de cartera: periodo, NIT, estado, razón social,
     * número de factura, rango de fechas, exclusión de residenciales masivas y saldo pendiente (Sprint 9, HU-57, HU-58, HU-60).
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<List<FacturaResumenDto>> listInvoices(
            @RequestParam(name = "periodo", required = false) String periodo,
            @RequestParam(name = "nit", required = false) String nit,
            @RequestParam(name = "estado", required = false) String estado,
            @RequestParam(name = "nombre", required = false) String nombre,
            @RequestParam(name = "razonSocial", required = false) String razonSocial,
            @RequestParam(name = "numeroFactura", required = false) String numeroFactura,
            @RequestParam(name = "fechaDesde", required = false) LocalDate fechaDesde,
            @RequestParam(name = "fechaHasta", required = false) LocalDate fechaHasta,
            @RequestParam(name = "incluirResidenciales", required = false, defaultValue = "false") boolean incluirResidenciales,
            @RequestParam(name = "soloConSaldo", required = false, defaultValue = "false") boolean soloConSaldo,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "50") int size
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isOnlyPasarela = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROL_PASARELA") || a.getAuthority().equals("ROLE_PASARELA"))
                && auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("INVOICES_VIEW"));

        if (isOnlyPasarela && (nit == null || nit.isBlank())) {
            throw new AccessDeniedException("El rol ROL_PASARELA requiere especificar el NIT para consultar facturas pendientes");
        }
        InvoiceStatus statusFilter = null;
        if (estado != null && !estado.isBlank()) {
            try {
                statusFilter = InvoiceStatus.valueOf(estado.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                for (InvoiceStatus s : InvoiceStatus.values()) {
                    if (s.getDbValue().equalsIgnoreCase(estado.trim())) {
                        statusFilter = s;
                        break;
                    }
                }
            }
        }

        List<Invoice> invoices = billingService.getInvoices(periodo, nit, statusFilter);
        String nameFilter = (nombre != null && !nombre.isBlank()) ? nombre.trim().toLowerCase() :
                ((razonSocial != null && !razonSocial.isBlank()) ? razonSocial.trim().toLowerCase() : null);

        var stream = invoices.stream()
                // 1. Filtro de razón social / nombre (HU-57)
                .filter(inv -> {
                    if (nameFilter == null) return true;
                    return inv.getClientBusinessName() != null
                            && inv.getClientBusinessName().toLowerCase().contains(nameFilter);
                })
                // 2. Filtro de número de factura (HU-57)
                .filter(inv -> {
                    if (numeroFactura == null || numeroFactura.isBlank()) return true;
                    return inv.getInvoiceNumber() != null
                            && inv.getInvoiceNumber().trim().equalsIgnoreCase(numeroFactura.trim());
                })
                // 3. Filtro de rango de fechas de emisión (HU-57)
                .filter(inv -> {
                    if (fechaDesde == null) return true;
                    return inv.getIssueDate() != null && !inv.getIssueDate().isBefore(fechaDesde);
                })
                .filter(inv -> {
                    if (fechaHasta == null) return true;
                    return inv.getIssueDate() != null && !inv.getIssueDate().isAfter(fechaHasta);
                })
                // 4. Excluir residenciales masivas por defecto (HU-60)
                .filter(inv -> incluirResidenciales || !inv.isResidential())
                // 5. Enriquecer con notas crédito aplicadas para balance real (HU-58)
                .peek(inv -> {
                    try {
                        var cns = creditNoteService.getCreditNotesByInvoice(inv.getId());
                        com.sistemaspalacios.billing.domain.vo.Money totalCn = com.sistemaspalacios.billing.domain.vo.Money.ZERO;
                        for (var cn : cns) {
                            if (cn.getStatus() == com.sistemaspalacios.billing.domain.model.AdjustmentDocumentStatus.APPLIED) {
                                totalCn = totalCn.plus(cn.getNetAmount());
                            }
                        }
                        inv.setCreditNoteAmount(totalCn);
                    } catch (Exception ignored) {}
                })
                // 6. Si soloConSaldo está activo, excluir facturas pagadas o con saldo 0 tras notas de crédito (HU-58)
                .filter(inv -> {
                    if (!soloConSaldo) return true;
                    return inv.getStatus() != InvoiceStatus.VOIDED && !inv.isFullyPaidOrCrossed();
                });

        // Ordenar por fecha de emisión descendente y paginar
        int pageSize = Math.max(1, size);
        int pageOffset = Math.max(0, page) * pageSize;

        List<FacturaResumenDto> dtos = stream
                .sorted((a, b) -> {
                    if (a.getIssueDate() == null || b.getIssueDate() == null) return 0;
                    return b.getIssueDate().compareTo(a.getIssueDate());
                })
                .skip(pageOffset)
                .limit(pageSize)
                .map(inv -> FacturaResumenDto.fromDomain(inv, periodo != null ? periodo : java.time.YearMonth.from(inv.getIssueDate()).toString()))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    /**
     * Obtener el detalle completo de una factura consolidada.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<FacturaDetalleDto> getInvoiceById(@PathVariable("id") UUID id) {
        Invoice invoice = billingService.getInvoiceById(id);
        com.sistemaspalacios.billing.domain.model.Payment payment = null;
        if (invoice.getStatus() == InvoiceStatus.PAID && paymentService != null) {
            var payments = paymentService.getPaymentsForInvoice(invoice.getId());
            if (payments != null && !payments.isEmpty()) {
                payment = payments.get(0);
            }
        }
        return ResponseEntity.ok(FacturaDetalleDto.fromDomain(invoice, java.time.YearMonth.from(invoice.getIssueDate()).toString(), payment));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sistemaspalacios.billing.service.InvoiceChargeBreakdownService chargeBreakdownService;

    /**
     * Detalle de cobros por bloques: Deuda anterior, Cargos fijos (plan de cada sede) y
     * Otros conceptos (novedades, cuotas y descuentos). Los bloques suman el total de la factura.
     */
    @GetMapping("/{id}/detalle-cobros")
    @PreAuthorize("hasAuthority('INVOICES_VIEW')")
    public ResponseEntity<com.sistemaspalacios.billing.service.dto.ChargeBreakdownDto> getChargeBreakdown(@PathVariable("id") UUID id) {
        if (chargeBreakdownService == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(chargeBreakdownService.breakdown(billingService.getInvoiceById(id)));
    }

    /**
     * Generar la representación gráfica oficial en HTML fiel al diseño del PDF Siigo F001000000519930000.PDF.
     */
    @GetMapping(value = {"/{id}/representacion-grafica", "/{id}/html"}, produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasAuthority('INVOICES_VIEW')")
    public ResponseEntity<String> renderInvoiceGraphic(@PathVariable("id") UUID id) {
        String html = billingService.renderInvoiceHtml(id);
        return ResponseEntity.ok(html);
    }

    /**
     * Descargar o visualizar el documento PDF oficial de la factura electrónica.
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<byte[]> getInvoicePdf(
            @PathVariable("id") UUID id,
            // inline: previsualizar en el navegador; attachment (por omisión): descargar.
            @RequestParam(name = "disposition", required = false, defaultValue = "attachment") String dispositionType
    ) {
        Invoice inv = billingService.getInvoiceById(id);
        String filename = buildPdfFilename(inv);
        ContentDisposition disposition = ("inline".equalsIgnoreCase(dispositionType)
                ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                .build();

        if (inv.getPdfPath() != null && !inv.getPdfPath().isBlank()) {
            // La ruta se resuelve solo desde la propia factura (nunca desde la petición) y el
            // contenido debe ser un PDF: así no se sirve otro archivo aunque la ruta guardada esté mal.
            java.io.File file = new java.io.File(inv.getPdfPath());
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                log.error("El archivo PDF asociado a la factura {} no existe o es ilegible en la ruta: {}", id, inv.getPdfPath());
                throw new InvoicePdfStorageException("El archivo PDF asociado a la factura no se encuentra disponible o no se puede leer");
            }
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
                    log.error("El archivo asociado a la factura {} no es un PDF válido: {}", id, inv.getPdfPath());
                    throw new InvoicePdfStorageException("El archivo asociado a la factura no es un PDF válido");
                }
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(bytes);
            } catch (InvoicePdfStorageException ex) {
                throw ex;
            } catch (Exception ex) {
                log.error("Error al leer el archivo PDF de la factura {} en {}: {}", id, inv.getPdfPath(), ex.getMessage(), ex);
                throw new InvoicePdfStorageException("Error al leer el archivo PDF asociado a la factura: " + ex.getMessage(), ex);
            }
        }

        // El backend no depende de recibir el PDF: genera la representación gráfica de ESTA factura
        // (su servicio, sus ítems y sus valores) a partir de sus propios datos.
        byte[] pdfBytes = billingService.renderInvoicePdf(id);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * Descargar o visualizar la imagen PNG del código QR original de la factura.
     * Soporta consultar por UUID, o por identificador de documento (ej: F1-51993, 51993, F20-287).
     */
    @GetMapping(value = {"/{ref}/qr.png", "/{ref}/qr"}, produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<byte[]> getInvoiceQrImage(@PathVariable("ref") String ref) {
        byte[] bytes = billingService.getInvoiceQrImage(ref);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(bytes);
    }

    private String buildPdfFilename(Invoice inv) {
        String number = inv.getInvoiceNumber();
        if (number != null && !number.isBlank()) {
            String consecutive = normalizedNumber(number);
            if (consecutive.isBlank()) {
                consecutive = number.trim().replace(" ", "_");
            }
            String type = inv.getBillingType() != null ? inv.getBillingType().name() : "";
            if (!type.isBlank()) {
                return type + "-" + consecutive + ".pdf";
            }
            return consecutive + ".pdf";
        }
        return inv.getId().toString() + ".pdf";
    }

    private String normalizedNumber(String number) {
        if (number == null) return "";
        var matcher = Pattern.compile("(\\d+)$").matcher(number.trim());
        return matcher.find() ? matcher.group(1).replaceFirst("^0+(?!$)", "") : "";
    }

    /**
     * Descargar el PDF oficial directamente por NIT de cliente y periodo.
     */
    @GetMapping(value = "/clientes/{nit}/facturas/{periodo}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<byte[]> getClientInvoicePdf(
            @PathVariable("nit") String nit,
            @PathVariable("periodo") String periodo
    ) {
        List<Invoice> invoices = billingService.getInvoices(periodo, nit, null);
        if (invoices.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (invoices.size() > 1) throw new IllegalArgumentException("Hay varias facturas para el periodo; seleccione el documento por su ID");
        return getInvoicePdf(invoices.get(0).getId(), "attachment");
    }

    /**
     * Anular factura emitida (Sprint 5, HU-19).
     */
    @PostMapping("/{id}/anular")
    @PreAuthorize("hasAuthority('INVOICES_DELETE')")
    public ResponseEntity<Map<String, Object>> voidInvoice(
            @PathVariable("id") UUID id,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = (body != null && body.containsKey("motivo") && !body.get("motivo").isBlank())
                ? body.get("motivo").trim()
                : "Anulación administrativa";
        billingService.voidInvoice(id, reason);
        Invoice inv = billingService.getInvoiceById(id);
        return ResponseEntity.ok(Map.of(
                "id", id,
                "estado", "ANULADA",
                "motivo", reason,
                "anuladoEn", inv.getVoidedAt() != null ? inv.getVoidedAt().toString() : ""
        ));
    }

    /**
     * Regenerar la factura creando una NUEVA VERSIÓN que acumula las novedades del
     * periodo (Sprint 10).
     * <p>
     * La versión anterior no se pierde: queda en estado REEMPLAZADA, conserva su PDF
     * oficial y se puede consultar en {@code GET /{id}/versiones}. La respuesta devuelve
     * la nueva versión vigente, que es la que el cliente debe pagar.
     */
    @PostMapping("/{id}/regenerar")
    @PreAuthorize("hasAuthority('INVOICES_UPDATE')")
    public ResponseEntity<Map<String, Object>> regenerateInvoice(
            @PathVariable("id") UUID id,
            @Valid @RequestBody RegenerarFacturaRequest request
    ) {
        Invoice previous = billingService.getInvoiceById(id);
        java.math.BigDecimal previousTotal = previous.getTotalAmount() != null
                ? previous.getTotalAmount().amount()
                : java.math.BigDecimal.ZERO;
        int previousVersion = previous.getVersionNumber();
        String previousNumber = previous.getInvoiceNumber();

        Invoice regenerated = billingService.regenerateInvoice(
                id, request.motivo(), request.resolvedConfirmarCufeTransmitido());

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("facturaVigente", FacturaResumenDto.fromDomain(regenerated, null));
        body.put("versionAnterior", Map.of(
                "id", id,
                "version", previousVersion,
                "numeroFactura", previousNumber != null ? previousNumber : "",
                "estado", InvoiceStatus.SUPERSEDED.getDbValue(),
                "valorTotal", previousTotal
        ));
        body.put("diferenciaValorTotal",
                regenerated.getTotalAmount().amount().subtract(previousTotal));
        body.put("motivo", request.motivo());

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Consultar la cadena completa de versiones de una factura, de la versión 1 a la
     * vigente (Sprint 10). Sirve para auditar qué se le cobró al cliente en cada revisión
     * del mismo periodo y con qué motivo cambió.
     */
    @GetMapping("/{id}/versiones")
    @PreAuthorize("hasAuthority('INVOICES_VIEW')")
    public ResponseEntity<List<FacturaVersionDto>> getInvoiceVersions(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(FacturaVersionDto.fromChain(billingService.getInvoiceVersions(id)));
    }

    /**
     * Obtener la factura VIGENTE de un cliente por NIT y periodo: la única que no está
     * anulada ni reemplazada, con su valor total y el detalle de los planes o suscripciones
     * activas que la componen (Sprint 10).
     */
    @GetMapping("/clientes/{nit}/periodos/{periodo}/vigente")
    @PreAuthorize("hasAnyAuthority('INVOICES_VIEW', 'ROL_PASARELA', 'ROLE_PASARELA')")
    public ResponseEntity<FacturaDetalleDto> getCurrentInvoiceForClient(
            @PathVariable("nit") String nit,
            @PathVariable("periodo") String periodo
    ) {
        Invoice invoice = billingService.getCurrentInvoiceForClientPeriod(nit, periodo);
        return ResponseEntity.ok(FacturaDetalleDto.fromDomain(invoice, periodo));
    }

    /**
     * Emitir nota crédito ligada a una factura (Sprint 5, HU-20).
     */
    @PostMapping("/{id}/notas-credito")
    @PreAuthorize("hasAuthority('INVOICES_UPDATE')")
    public ResponseEntity<com.sistemaspalacios.billing.web.dto.NotaCreditoResponseDto> issueCreditNote(
            @PathVariable("id") UUID id,
            @Valid @RequestBody com.sistemaspalacios.billing.web.dto.EmitirNotaCreditoRequest request
    ) {
        com.sistemaspalacios.billing.domain.model.AdjustmentDocument doc = creditNoteService.issueCreditNote(
                id,
                request.motivo(),
                request.numeroDocumento(),
                request.toCommands()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(com.sistemaspalacios.billing.web.dto.NotaCreditoResponseDto.fromDomain(doc));
    }

    /**
     * Consultar notas de crédito asociadas a una factura (Sprint 5, HU-20).
     */
    @GetMapping("/{id}/notas-credito")
    @PreAuthorize("hasAuthority('INVOICES_VIEW')")
    public ResponseEntity<List<com.sistemaspalacios.billing.web.dto.NotaCreditoResponseDto>> getCreditNotes(
            @PathVariable("id") UUID id
    ) {
        List<com.sistemaspalacios.billing.web.dto.NotaCreditoResponseDto> response = creditNoteService.getCreditNotesByInvoice(id)
                .stream()
                .map(com.sistemaspalacios.billing.web.dto.NotaCreditoResponseDto::fromDomain)
                .toList();
        return ResponseEntity.ok(response);
    }
}
