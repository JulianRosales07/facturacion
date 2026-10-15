package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CarteraSnapshot;
import com.sistemaspalacios.billing.domain.model.CarteraSnapshotLine;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCarteraSnapshot;
import com.sistemaspalacios.billing.repository.RepositorioCarteraSnapshotLinea;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import com.sistemaspalacios.billing.service.siigo.SiigoCarteraReader;
import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ingesta del corte de cartera de Siigo desde la carpeta compartida (Sprint 13).
 * <p>
 * <b>Decisión de negocio que gobierna esta clase:</b> la columna SALDO del archivo es la
 * <b>fuente de verdad</b> del saldo pendiente. El saldo que el microservicio calcula
 * (valor total − notas crédito − pagos) pasa a ser una proyección local que solo se usa para
 * conciliar y mostrar diferencias.
 * <p>
 * Consecuencias que se manejan explícitamente:
 * <ul>
 *   <li><b>Cortes obsoletos.</b> El archivo es una foto. Si llega uno generado antes del
 *       saldo ya aplicado, se rechaza fila por fila para que un corte de ayer no revierta la
 *       cartera de hoy.</li>
 *   <li><b>Ausencia del archivo.</b> Que una factura no aparezca en el corte <i>podría</i>
 *       significar que está pagada, pero también que el extracto es parcial. Por defecto
 *       <b>no</b> se asume saldo cero: se cuenta y se reporta. La política se cambia con
 *       {@code siigo.cartera.tratar-ausencia-como-saldo-cero}.</li>
 *   <li><b>Saldo cero.</b> Si Siigo dice que no se debe nada, la factura pasa a
 *       {@code PAGADA} aunque el pago se haya registrado fuera del microservicio. Es la
 *       consecuencia directa de la decisión, y queda auditada en la línea del corte.</li>
 * </ul>
 */
@Service
public class CarteraSyncService {

    private static final Logger log = LoggerFactory.getLogger(CarteraSyncService.class);

    private final SiigoSharedFolderReader sharedFolderReader;
    private final SiigoCarteraReader carteraReader;
    private final RepositorioFactura repositorioFactura;
    private final RepositorioCarteraSnapshot repositorioCarteraSnapshot;
    private final RepositorioCarteraSnapshotLinea repositorioCarteraSnapshotLinea;
    private final CurrentUserProvider currentUserProvider;

    /**
     * Si es {@code true}, una factura vigente ausente del corte se interpreta como saldo cero.
     * Por defecto {@code false}: un extracto parcial marcaría cientos de facturas como pagadas.
     */
    private final boolean treatAbsenceAsZeroBalance;

    @Autowired
    public CarteraSyncService(
            SiigoSharedFolderReader sharedFolderReader,
            SiigoCarteraReader carteraReader,
            RepositorioFactura repositorioFactura,
            RepositorioCarteraSnapshot repositorioCarteraSnapshot,
            RepositorioCarteraSnapshotLinea repositorioCarteraSnapshotLinea,
            @Autowired(required = false) CurrentUserProvider currentUserProvider,
            @Value("${siigo.cartera.tratar-ausencia-como-saldo-cero:false}") boolean treatAbsenceAsZeroBalance
    ) {
        this.sharedFolderReader = Objects.requireNonNull(sharedFolderReader);
        this.carteraReader = Objects.requireNonNull(carteraReader);
        this.repositorioFactura = Objects.requireNonNull(repositorioFactura);
        this.repositorioCarteraSnapshot = Objects.requireNonNull(repositorioCarteraSnapshot);
        this.repositorioCarteraSnapshotLinea = Objects.requireNonNull(repositorioCarteraSnapshotLinea);
        this.currentUserProvider = currentUserProvider;
        this.treatAbsenceAsZeroBalance = treatAbsenceAsZeroBalance;
    }

    /**
     * Procesa todos los cortes de cartera presentes en la carpeta compartida.
     * Los archivos ya ingeridos (mismo hash) se omiten.
     */
    @Transactional
    public CarteraSyncSummary processSharedFolderSnapshots() {
        List<File> spreadsheets = sharedFolderReader.listCarteraSpreadsheets();
        log.info("Escaneando cortes de cartera en la carpeta compartida: {} archivo(s)", spreadsheets.size());

        List<CarteraSnapshotResult> results = new ArrayList<>();
        int skipped = 0;

        for (File file : spreadsheets) {
            String hash = sha256Of(file);
            if (hash != null && repositorioCarteraSnapshot.existsBySha256Hash(hash)) {
                log.debug("Corte {} ya fue ingerido previamente (hash coincide); se omite", file.getName());
                skipped++;
                continue;
            }
            results.add(ingest(file, hash));
        }

        return new CarteraSyncSummary(spreadsheets.size(), results.size(), skipped, results);
    }

    /**
     * Ingiere un corte concreto: lee, empareja por (código de comprobante, consecutivo),
     * valida el NIT y aplica el saldo.
     */
    @Transactional
    public CarteraSnapshotResult ingest(File file, String precomputedHash) {
        String user = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        String hash = precomputedHash != null ? precomputedHash : sha256Of(file);

        SiigoCarteraReader.CarteraSnapshotData data = carteraReader.read(file);

        if (data.cutoffDate() == null) {
            throw new IllegalArgumentException("El Excel no informa la fecha de generación del corte. Impórtelo desde el panel indicando fecha y hora; no se usará la fecha de una factura como corte.");
        }

        // El corte se fecha por su contenido (la fecha de documento más reciente), no por la
        // hora de ingesta: así un archivo viejo copiado hoy no se confunde con uno nuevo.
        LocalDate cutoffDate = data.cutoffDate() != null ? data.cutoffDate() : LocalDate.now();
        Instant cutoffAt = cutoffDate.atStartOfDay(ZoneId.systemDefault()).toInstant();

        CarteraSnapshot snapshot = new CarteraSnapshot(
                null, file.getName(), file.getAbsolutePath(), hash, file.length(), cutoffAt, user);

        if (data.rows().isEmpty() && !data.errors().isEmpty()) {
            snapshot.fail("No se pudo interpretar ninguna fila del corte");
            data.errors().forEach(e -> snapshot.addLine(
                    CarteraSnapshotLine.formatError(e.rowNumber(), e.message())));
            repositorioCarteraSnapshot.saveAndFlush(snapshot);
            return new CarteraSnapshotResult(snapshot.getId(), file.getName(), cutoffDate,
                    0, 0, 0, data.errors().size(), 0, List.of());
        }

        // Índice de facturas por (tipo de facturación, consecutivo normalizado).
        Map<String, Invoice> invoiceIndex = buildInvoiceIndex();

        int matched = 0;
        int unmatched = 0;
        int stale = 0;
        Set<UUID> touchedInvoices = new HashSet<>();
        List<Invoice> toPersist = new ArrayList<>();
        List<CarteraDiscrepancy> discrepancies = new ArrayList<>();

        for (SiigoCarteraReader.CarteraRow row : data.rows()) {
            CarteraSnapshotLine line = toLine(row);

            Invoice invoice = invoiceIndex.get(indexKey(row.billingType(), row.documentNumber()));
            if (invoice == null) {
                line.markNoInvoice(String.format(
                        "Siigo reporta el documento %s (comprobante %s) que no existe en el sistema",
                        row.documentNumber(), row.voucherCode()));
                unmatched++;
                snapshot.addLine(line);
                continue;
            }

            // Validación cruzada del NIT: no bloquea, pero deja constancia.
            if (!nitMatches(invoice.getClientNit(), row.clientNit())) {
                line.markNitMismatch(invoice.getId(), String.format(
                        "El NIT del corte (%s) no coincide con el de la factura (%s)",
                        row.clientNit(), invoice.getClientNit()));
                log.warn("Corte de cartera fila {}: NIT discrepante para el documento {} ({} vs {})",
                        row.rowNumber(), row.documentNumber(), row.clientNit(), invoice.getClientNit());
                snapshot.addLine(line);
                unmatched++;
                continue;
            }

            Money computedBefore = invoice.getComputedPendingBalance();
            boolean applied = invoice.applySiigoBalance(row.balance(), cutoffAt, snapshot.getId());

            if (!applied) {
                line.markStaleSnapshot(invoice.getId(), String.format(
                        "El corte es anterior (%s) al saldo ya aplicado (%s); no se sobrescribe",
                        cutoffDate, invoice.getSiigoBalanceCutoffAt()));
                stale++;
                snapshot.addLine(line);
                continue;
            }

            if (row.dueDate() != null) invoice.setDueDate(row.dueDate());
            line.markApplied(invoice.getId(), computedBefore);
            matched++;
            touchedInvoices.add(invoice.getId());
            toPersist.add(invoice);
            snapshot.addLine(line);

            if (line.hasDiscrepancy()) {
                discrepancies.add(new CarteraDiscrepancy(
                        invoice.getId(),
                        invoice.getInvoiceNumber(),
                        invoice.getClientNit(),
                        row.balance(),
                        computedBefore,
                        line.getDifference()));
            }
        }

        data.errors().forEach(e -> snapshot.addLine(
                CarteraSnapshotLine.formatError(e.rowNumber(), e.message())));

        // Facturas vigentes que el corte no menciona.
        List<Invoice> absent = invoiceIndex.values().stream()
                .distinct()
                .filter(i -> !touchedInvoices.contains(i.getId()))
                .filter(i -> !i.getPendingBalance().isZero())
                .toList();

        if (treatAbsenceAsZeroBalance) {
            for (Invoice invoice : absent) {
                if (invoice.applySiigoBalance(Money.ZERO, cutoffAt, snapshot.getId())) {
                    toPersist.add(invoice);
                }
            }
            log.info("Política de ausencia activa: {} facturas ausentes del corte quedaron en saldo cero", absent.size());
        } else if (!absent.isEmpty()) {
            log.info("{} facturas vigentes no aparecen en el corte {}. NO se asumen pagadas "
                            + "(siigo.cartera.tratar-ausencia-como-saldo-cero=false)",
                    absent.size(), file.getName());
        }

        if (!toPersist.isEmpty()) {
            repositorioFactura.saveAll(toPersist);
        }

        String errorSummary = data.errors().isEmpty() && stale == 0
                ? null
                : String.format("%d fila(s) con error de formato, %d fila(s) con corte obsoleto",
                data.errors().size(), stale);

        snapshot.complete(data.totalRows(), matched, unmatched, data.errors().size(),
                absent.size(), errorSummary);
        repositorioCarteraSnapshot.saveAndFlush(snapshot);

        log.info("Corte {} aplicado: {} emparejadas, {} sin factura, {} obsoletas, {} errores, "
                        + "{} facturas ausentes, {} discrepancias de saldo",
                file.getName(), matched, unmatched, stale, data.errors().size(),
                absent.size(), discrepancies.size());

        return new CarteraSnapshotResult(snapshot.getId(), file.getName(), cutoffDate,
                data.totalRows(), matched, unmatched, data.errors().size(), absent.size(), discrepancies);
    }

    // --- Lecturas de conciliación (no disparan ingesta) ------------------------

    /**
     * Conciliación del último corte ingerido. Lectura pura sobre los valores congelados en
     * cada fila al momento de aplicarla.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport getLatestReconciliation() {
        var snapshotOpt = repositorioCarteraSnapshot.findTopByOrderByGeneratedAtDesc();
        if (snapshotOpt.isEmpty()) {
            return new ReconciliationReport(null, null, null, List.of(), Money.ZERO);
        }
        CarteraSnapshot snapshot = snapshotOpt.get();

        List<CarteraDiscrepancy> discrepancies = new ArrayList<>();
        Money net = Money.ZERO;
        for (CarteraSnapshotLine line : repositorioCarteraSnapshotLinea.findBySnapshotId(snapshot.getId())) {
            if (!line.hasDiscrepancy()) {
                continue;
            }
            Invoice invoice = line.getInvoiceId() != null
                    ? repositorioFactura.findById(line.getInvoiceId()).orElse(null)
                    : null;
            discrepancies.add(new CarteraDiscrepancy(
                    line.getInvoiceId(),
                    invoice != null ? invoice.getInvoiceNumber() : line.getDocumentNumber(),
                    invoice != null ? invoice.getClientNit() : line.getFileNit(),
                    line.getBalance(),
                    line.getComputedBalance(),
                    line.getDifference()));
            net = net.plus(line.getDifference());
        }

        return new ReconciliationReport(
                snapshot.getId(), snapshot.getFileName(), snapshot.getGeneratedAt(), discrepancies, net);
    }

    /** Filas del último corte que quedaron sin aplicar, con el motivo. */
    @Transactional(readOnly = true)
    public Map<String, Object> getLatestUnresolvedRows() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        var snapshotOpt = repositorioCarteraSnapshot.findTopByOrderByGeneratedAtDesc();
        if (snapshotOpt.isEmpty()) {
            body.put("corte", null);
            body.put("total", 0);
            body.put("filas", List.of());
            return body;
        }
        CarteraSnapshot snapshot = snapshotOpt.get();
        List<CarteraSnapshotLine> unresolved =
                repositorioCarteraSnapshotLinea.findUnresolvedBySnapshot(snapshot.getId());

        body.put("corte", snapshot.getFileName());
        body.put("fechaCorte", snapshot.getGeneratedAt());
        body.put("total", unresolved.size());
        body.put("filas", unresolved.stream().map(l -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("fila", l.getRowNumber());
            row.put("estado", l.getLineStatus());
            row.put("nitArchivo", l.getFileNit());
            row.put("numeroDocumento", l.getDocumentNumber());
            row.put("tipoFacturacion", l.getBillingType());
            row.put("saldo", l.getBalance() != null ? l.getBalance().amount() : null);
            row.put("mensaje", l.getMessage());
            return row;
        }).toList());
        return body;
    }

    /**
     * @param netDifference suma de todas las diferencias. Si es cero pero hay filas
     *                      discrepantes, significa que las diferencias se compensan entre sí
     *                      y conviene revisarlas una por una.
     */
    public record ReconciliationReport(
            UUID snapshotId,
            String fileName,
            Instant cutoffAt,
            List<CarteraDiscrepancy> discrepancies,
            Money netDifference
    ) {
        public Map<String, Object> toResponse() {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("snapshotId", snapshotId);
            body.put("corte", fileName);
            body.put("fechaCorte", cutoffAt);
            body.put("totalDiferencias", discrepancies.size());
            body.put("diferenciaNeta", netDifference.amount());
            body.put("diferencias", discrepancies.stream().map(d -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("facturaId", d.invoiceId());
                row.put("numeroFactura", d.invoiceNumber());
                row.put("nit", d.clientNit());
                row.put("saldoSiigo", d.siigoBalance() != null ? d.siigoBalance().amount() : null);
                row.put("saldoCalculado", d.computedBalance() != null ? d.computedBalance().amount() : null);
                row.put("diferencia", d.difference() != null ? d.difference().amount() : null);
                return row;
            }).toList());
            return body;
        }
    }

    /**
     * Facturas indexadas por tipo de facturación y consecutivo, tal como los identifica el
     * corte. Se excluyen las anuladas; las versiones reemplazadas sí se indexan porque
     * conservan su propio número y Siigo puede seguir reportándolas.
     */
    private Map<String, Invoice> buildInvoiceIndex() {
        Map<String, Invoice> index = new HashMap<>();
        for (Invoice invoice : repositorioFactura.findAll()) {
            if (invoice.getStatus() == com.sistemaspalacios.billing.domain.model.InvoiceStatus.VOIDED) {
                continue;
            }
            String number = invoice.getInvoiceNumber();
            if (number == null || number.isBlank()) {
                continue;
            }
            String key = indexKey(invoice.getBillingType(), number);
            // La versión vigente gana sobre una reemplazada con el mismo consecutivo.
            Invoice existing = index.get(key);
            if (existing == null || (!existing.isCurrentVersion() && invoice.isCurrentVersion())) {
                index.put(key, invoice);
            }
        }
        return index;
    }

    /** Normaliza a dígitos: {@code "FESP 51993"} y {@code "51993"} producen la misma clave. */
    private String indexKey(BillingType billingType, String documentNumber) {
        String digits = documentNumber != null ? documentNumber.replaceAll("[^0-9]", "") : "";
        String normalized = digits.replaceFirst("^0+(?!$)", "");
        return (billingType != null ? billingType.name() : "F1") + "#" + normalized;
    }

    private boolean nitMatches(String invoiceNit, String fileNit) {
        if (invoiceNit == null || fileNit == null) {
            return true; // sin dato con el que comparar, no se bloquea
        }
        String a = invoiceNit.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", "");
        String b = fileNit.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", "");
        if (a.isBlank() || b.isBlank()) {
            return true;
        }
        // Se compara sin dígito de verificación: el archivo puede traerlo o no.
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    private CarteraSnapshotLine toLine(SiigoCarteraReader.CarteraRow row) {
        CarteraSnapshotLine line = new CarteraSnapshotLine(null, row.rowNumber());
        line.setFileNit(row.clientNit());
        line.setAccountingAccount(row.accountingAccount());
        line.setVoucherType(row.voucherType());
        line.setVoucherCode(row.voucherCode());
        line.setDocumentNumber(row.documentNumber());
        line.setBillingType(row.billingType());
        line.setBalance(row.balance());
        line.setDueDate(row.dueDate());
        line.setDocumentDate(row.documentDate());
        line.setCostCenter(row.costCenter());
        line.setCityCode(row.cityCode());
        line.setZoneCode(row.zoneCode());
        return line;
    }

    private String sha256Of(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            log.warn("No se pudo calcular el hash de {}: {}", file.getName(), ex.getMessage());
            return null;
        }
    }

    // --- DTOs de resultado -----------------------------------------------------

    /** Diferencia entre el saldo de Siigo y el que el microservicio había calculado. */
    public record CarteraDiscrepancy(
            UUID invoiceId,
            String invoiceNumber,
            String clientNit,
            Money siigoBalance,
            Money computedBalance,
            Money difference
    ) {}

    public record CarteraSnapshotResult(
            UUID snapshotId,
            String fileName,
            LocalDate cutoffDate,
            int totalRows,
            int appliedRows,
            int unmatchedRows,
            int errorRows,
            int invoicesAbsentFromSnapshot,
            List<CarteraDiscrepancy> discrepancies
    ) {}

    public record CarteraSyncSummary(
            int filesFound,
            int filesIngested,
            int filesSkippedAsDuplicate,
            List<CarteraSnapshotResult> results
    ) {
        public int totalAppliedRows() {
            return results.stream().mapToInt(CarteraSnapshotResult::appliedRows).sum();
        }
    }
}
