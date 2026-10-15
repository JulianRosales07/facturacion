package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de auditoría y evaluación de disponibilidad de facturas mensuales por cliente.
 * <p>
 * Reglas de negocio:
 * 1. Para clientes ordinarios, la factura del periodo en curso debe estar disponible
 *    para pago a más tardar el día 1 o 2 de ese mismo mes (sujeto a importación de Siigo).
 * 2. Si transcurre el día 2 sin que la factura haya sido importada desde Siigo, se identifica
 *    y reporta la incidencia como FALTANTE, SIN inventar importes ni crear documentos ficticios.
 * 3. Si una factura se importa tarde, se conserva su periodo de servicio original y se registra el retraso.
 * 4. Los clientes corporativos con regla especial siguen sus condiciones contractuales y no son
 *    afectados por la regla ordinaria general.
 */
@Service
@Transactional(readOnly = true)
public class InvoiceAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceAvailabilityService.class);

    public enum AvailabilityStatus {
        DISPONIBLE_A_TIEMPO,
        DISPONIBLE_CON_RETRASO,
        INCIDENCIA_FACTURA_FALTANTE,
        PENDIENTE_DENTRO_DEL_PLAZO,
        REGLA_CORPORATIVA_ESPECIAL,
        CONFIGURACION_INCOMPLETA
    }

    public record ClientPeriodAvailabilityReport(
            String nit,
            String businessName,
            ClientBillingRule billingRule,
            String corporateRuleDetails,
            Integer corporateCutoffDay,
            YearMonth period,
            LocalDate evaluationDate,
            AvailabilityStatus status,
            boolean availableForPayment,
            String invoiceNumber,
            String observations
    ) {
        public ClientPeriodAvailabilityReport(
                String nit,
                String businessName,
                ClientBillingRule billingRule,
                String corporateRuleDetails,
                YearMonth period,
                LocalDate evaluationDate,
                AvailabilityStatus status,
                boolean availableForPayment,
                String invoiceNumber,
                String observations
        ) {
            this(nit, businessName, billingRule, corporateRuleDetails, null, period, evaluationDate, status, availableForPayment, invoiceNumber, observations);
        }
    }

    private final RepositorioCliente repositorioCliente;
    private final RepositorioFactura repositorioFactura;
    private final RepositorioPeriodo repositorioPeriodo;

    public static final java.time.ZoneId BUSINESS_ZONE = java.time.ZoneId.of("America/Bogota");

    public static java.util.OptionalInt resolveCorporateCutoffDay(CorporateClient client, YearMonth period) {
        if (client.getCorporateCutoffDay() != null) {
            int day = client.getCorporateCutoffDay();
            if (day >= 1 && day <= 28) {
                return java.util.OptionalInt.of(day);
            }
        }
        if (client.getCorporateRuleDetails() != null) {
            var matcher = java.util.regex.Pattern.compile("(?:día|dia|corte|fecha)\\s*(\\d{1,2})", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(client.getCorporateRuleDetails());
            if (matcher.find()) {
                try {
                    int day = Integer.parseInt(matcher.group(1));
                    if (day >= 1 && day <= 28) {
                        return java.util.OptionalInt.of(day);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        // No se asigna valor predeterminado silencioso (se rechaza el día 15 automático).
        return java.util.OptionalInt.empty();
    }

    public InvoiceAvailabilityService(
            RepositorioCliente repositorioCliente,
            RepositorioFactura repositorioFactura,
            RepositorioPeriodo repositorioPeriodo
    ) {
        this.repositorioCliente = Objects.requireNonNull(repositorioCliente);
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura);
        this.repositorioPeriodo = Objects.requireNonNull(repositorioPeriodo);
    }

    /**
     * Evalúa la disponibilidad de la factura mensual para un cliente y periodo específicos
     * en una fecha de evaluación controlada.
     */
    public ClientPeriodAvailabilityReport evaluateClientAvailability(String nit, YearMonth period, LocalDate evaluationDate) {
        Objects.requireNonNull(nit, "nit cannot be null");
        Objects.requireNonNull(period, "period cannot be null");
        LocalDate evalDate = evaluationDate != null ? evaluationDate : LocalDate.now();

        CorporateClient client = repositorioCliente.findByNit(nit.trim())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado con NIT: " + nit));

        if (client.getBillingRule() == ClientBillingRule.ESPECIAL_CORPORATIVA) {
            java.util.OptionalInt cutoffOpt = resolveCorporateCutoffDay(client, period);
            if (cutoffOpt.isEmpty()) {
                AvailabilityStatus status = AvailabilityStatus.CONFIGURACION_INCOMPLETA;
                String obs = "INCIDENCIA ADMINISTRATIVA: Cliente corporativo especial sin día de corte contractual válido (1 a 28). " +
                        "Detalle registrado: " + (client.getCorporateRuleDetails() != null ? client.getCorporateRuleDetails() : "Sin detalle") +
                        ". Requiere configuración manual autorizada.";
                log.warn("Configuración incompleta: Cliente especial NIT {} no tiene corte contractual válido (1-28).", client.getNit());
                return new ClientPeriodAvailabilityReport(
                        client.getNit(), client.getBusinessName(), client.getBillingRule(), client.getCorporateRuleDetails(),
                        client.getCorporateCutoffDay(), period, evalDate, status, false, null, obs
                );
            }

            int cutoffDay = cutoffOpt.getAsInt();
            LocalDate corporateCutoff = period.atDay(cutoffDay);
            Optional<Invoice> invoiceOpt = findInvoiceForClientAndPeriod(client, period);

            if (invoiceOpt.isPresent()) {
                Invoice inv = invoiceOpt.get();
                boolean isDelayed = inv.isImportDelayRecorded();
                if (!isDelayed) {
                    java.time.Instant ts = inv.getAvailableForPaymentAt() != null ? inv.getAvailableForPaymentAt() : inv.getCreatedAt();
                    if (ts != null) {
                        LocalDate importDate = ts.atZone(java.time.ZoneOffset.UTC).toLocalDate();
                        if (importDate.isAfter(corporateCutoff)) {
                            isDelayed = true;
                        }
                    }
                }
                AvailabilityStatus status = isDelayed
                        ? AvailabilityStatus.DISPONIBLE_CON_RETRASO
                        : AvailabilityStatus.DISPONIBLE_A_TIEMPO;
                String obs = isDelayed
                        ? "Factura oficial " + inv.getInvoiceNumber() + " disponible para pago con retraso respecto al corte contractual (día " + cutoffDay + ")."
                        : "Factura oficial " + inv.getInvoiceNumber() + " disponible a tiempo dentro del ciclo contractual (corte día " + cutoffDay + ").";

                return new ClientPeriodAvailabilityReport(
                        client.getNit(), client.getBusinessName(), client.getBillingRule(), client.getCorporateRuleDetails(),
                        cutoffDay, period, evalDate, status, true, inv.getInvoiceNumber(), obs
                );
            } else {
                boolean pastCutoff = evalDate.isAfter(corporateCutoff);
                AvailabilityStatus status = pastCutoff
                        ? AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE
                        : AvailabilityStatus.REGLA_CORPORATIVA_ESPECIAL;

                String obs = pastCutoff
                        ? "INCIDENCIA: Factura del periodo " + period + " para cliente corporativo especial no ha sido importada tras vencer su fecha límite contractual (día " + cutoffDay + ", evaluado al " + evalDate + "). No se inventa documento ficticio."
                        : "Cliente corporativo bajo regla contractual especial (" + (client.getCorporateRuleDetails() != null ? client.getCorporateRuleDetails() : "corte día " + cutoffDay) + "). Dentro del plazo contractual acordado (evaluado al " + evalDate + ").";

                if (pastCutoff) {
                    log.warn("Incidencia contractual: Factura no importada para cliente especial NIT {} en periodo {} tras corte del día {}",
                            client.getNit(), period, cutoffDay);
                }

                return new ClientPeriodAvailabilityReport(
                        client.getNit(), client.getBusinessName(), client.getBillingRule(), client.getCorporateRuleDetails(),
                        cutoffDay, period, evalDate, status, false, null, obs
                );
            }
        }

        // Regla Ordinaria: disponibilidad esperada el día 1 o 2 del mes del periodo
        Optional<Invoice> invoiceOpt = findInvoiceForClientAndPeriod(client, period);
        LocalDate ordinaryCutoff = period.atDay(2);

        if (invoiceOpt.isPresent()) {
            Invoice inv = invoiceOpt.get();
            boolean isDelayed = inv.isImportDelayRecorded();
            if (!isDelayed) {
                java.time.Instant ts = inv.getAvailableForPaymentAt() != null ? inv.getAvailableForPaymentAt() : inv.getCreatedAt();
                if (ts != null) {
                    LocalDate importDate = ts.atZone(java.time.ZoneOffset.UTC).toLocalDate();
                    if (importDate.isAfter(ordinaryCutoff)) {
                        isDelayed = true;
                    }
                }
            }

            AvailabilityStatus status = isDelayed
                    ? AvailabilityStatus.DISPONIBLE_CON_RETRASO
                    : AvailabilityStatus.DISPONIBLE_A_TIEMPO;

            String obs = isDelayed
                    ? "Factura oficial " + inv.getInvoiceNumber() + " disponible para pago con retraso respecto al corte del día 2. Periodo original conservado."
                    : "Factura oficial " + inv.getInvoiceNumber() + " disponible a tiempo para pago en el periodo ordinario.";

            return new ClientPeriodAvailabilityReport(
                    client.getNit(),
                    client.getBusinessName(),
                    client.getBillingRule(),
                    client.getCorporateRuleDetails(),
                    2,
                    period,
                    evalDate,
                    status,
                    true,
                    inv.getInvoiceNumber(),
                    obs
            );
        } else {
            // No existe la factura en el sistema
            boolean pastCutoff = evalDate.isAfter(ordinaryCutoff);
            AvailabilityStatus status = pastCutoff
                    ? AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE
                    : AvailabilityStatus.PENDIENTE_DENTRO_DEL_PLAZO;

            String obs = pastCutoff
                    ? "INCIDENCIA: Factura del periodo " + period + " no ha sido importada desde Siigo al finalizar el día 2 (" + evalDate + "). No se inventa documento ficticio."
                    : "Factura del periodo " + period + " pendiente de importación desde Siigo dentro del plazo ordinario (día 1-2).";

            if (pastCutoff) {
                log.warn("Incidencia de disponibilidad: Factura no importada para cliente NIT {} en periodo {} al corte {}",
                        client.getNit(), period, evalDate);
            }

            return new ClientPeriodAvailabilityReport(
                    client.getNit(),
                    client.getBusinessName(),
                    client.getBillingRule(),
                    client.getCorporateRuleDetails(),
                    2,
                    period,
                    evalDate,
                    status,
                    false,
                    null,
                    obs
            );
        }
    }

    /**
     * Evalúa todos los clientes activos para un periodo y fecha de evaluación dados.
     */
    public List<ClientPeriodAvailabilityReport> evaluateAllActiveClients(YearMonth period, LocalDate evaluationDate) {
        List<CorporateClient> activeClients = repositorioCliente.findByStatus(RecordStatus.ACTIVO);
        List<ClientPeriodAvailabilityReport> reports = new ArrayList<>();
        for (CorporateClient client : activeClients) {
            reports.add(evaluateClientAvailability(client.getNit(), period, evaluationDate));
        }
        return reports;
    }

    /**
     * Retorna únicamente los clientes con incidencias de facturas faltantes al corte.
     */
    public List<ClientPeriodAvailabilityReport> findMissingInvoiceIncidents(YearMonth period, LocalDate evaluationDate) {
        return evaluateAllActiveClients(period, evaluationDate).stream()
                .filter(r -> r.status() == AvailabilityStatus.INCIDENCIA_FACTURA_FALTANTE)
                .toList();
    }

    private Optional<Invoice> findInvoiceForClientAndPeriod(CorporateClient client, YearMonth period) {
        List<Invoice> invoices = repositorioFactura.findByClientId(client.getId());
        String targetPeriodCode = period.toString();

        return invoices.stream()
                .filter(Invoice::isCurrentVersion)
                .filter(inv -> inv.getStatus() != InvoiceStatus.VOIDED && inv.getStatus() != InvoiceStatus.SUPERSEDED)
                .filter(inv -> {
                    String pCode = periodCodeOf(inv);
                    return targetPeriodCode.equals(pCode);
                })
                .findFirst();
    }

    private String periodCodeOf(Invoice invoice) {
        if (invoice.getPeriodId() != null) {
            var p = repositorioPeriodo.findById(invoice.getPeriodId());
            if (p.isPresent()) return p.get().getCode();
        }
        return invoice.getIssueDate() != null ? YearMonth.from(invoice.getIssueDate()).toString() : null;
    }
}
