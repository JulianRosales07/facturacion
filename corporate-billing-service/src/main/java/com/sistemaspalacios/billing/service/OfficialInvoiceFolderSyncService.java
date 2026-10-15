package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Servicio para sincronizar archivos de datos desde la carpeta compartida de Siigo.
 * Cada archivo se importa en su propia transacción; un fallo no bloquea los demás.
 * Se auditan los archivos omitidos (.txt, .csv, .dat) y se saltan los ya procesados por hash SHA-256.
 */
@Service
public class OfficialInvoiceFolderSyncService {

    private static final Logger log = LoggerFactory.getLogger(OfficialInvoiceFolderSyncService.class);

    private final IncomingFileIntakeService importer;
    private final SiigoSharedFolderReader folder;
    private final IncomingFileAuditService audit;
    private final boolean confirmOutgoingBatches;

    @Autowired
    public OfficialInvoiceFolderSyncService(
            IncomingFileIntakeService importer,
            SiigoSharedFolderReader folder,
            IncomingFileAuditService audit,
            @Value("${siigo.sync.confirmar-lotes-salientes:false}") boolean confirmOutgoingBatches
    ) {
        this.importer = importer;
        this.folder = folder;
        this.audit = audit;
        this.confirmOutgoingBatches = confirmOutgoingBatches;
    }

    public OfficialInvoiceFolderSyncService(IncomingFileIntakeService importer, SiigoSharedFolderReader folder, IncomingFileAuditService audit) {
        this(importer, folder, audit, false);
    }

    public OfficialInvoiceFolderSyncService(IncomingFileIntakeService importer, SiigoSharedFolderReader folder) {
        this(importer, folder, null, false);
    }

    public ScanResult scan() {
        if (!folder.isFolderAccessible()) {
            throw new IllegalStateException("Carpeta Siigo inaccesible: revise la ruta y permisos de lectura del servicio");
        }

        List<File> candidates = new ArrayList<>(folder.listCarteraSpreadsheets());
        candidates.addAll(folder.listConfirmationFlatFiles());

        List<String> errors = new ArrayList<>();
        int verified = 0;
        int skippedFiles = 0;
        int processedFiles = 0;

        for (File file : candidates) {
            try {
                long size = file.length();
                long modified = file.lastModified();
                if (size == 0 || size > 20L * 1024 * 1024) {
                    throw new IllegalArgumentException("Archivo vacío o mayor a 20 MB");
                }
                byte[] content = Files.readAllBytes(file.toPath());
                if (content.length != size || file.length() != size || file.lastModified() != modified) {
                    throw new IllegalArgumentException("Archivo en escritura; se reintentará en el próximo ciclo");
                }

                String hash = computeSha256(content);
                String nameLower = file.getName().toLowerCase(Locale.ROOT);

                // Tarea 3: Archivos planos no soportados aún (.txt, .csv, .dat)
                if (nameLower.endsWith(".txt") || nameLower.endsWith(".csv") || nameLower.endsWith(".dat")) {
                    String detail = confirmOutgoingBatches
                            ? "Dejado al módulo de lotes"
                            : "Formato no soportado todavía: requiere un adaptador para el diseño que entregue Siigo";

                    errors.add(file.getName() + ": " + detail);
                    if (audit != null && !audit.isAlreadyProcessed(hash)) {
                        audit.record(file.getName(), "CARPETA", "OMITIDO", hash, 0, 0, detail);
                    }
                    continue;
                }

                // Tarea 4: Salto por hash si ya está IMPORTADO u OMITIDO en la base
                if (audit != null && audit.isAlreadyProcessed(hash)) {
                    skippedFiles++;
                    log.debug("Archivo {} saltado por coincidencia de hash SHA-256 (ya procesado)", file.getName());
                    continue;
                }

                var result = importer.receive(file.getName(), content, "CARPETA");
                verified += result.invoiceIds().size();
                processedFiles++;

            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                errors.add(file.getName() + ": " + message.substring(0, Math.min(350, message.length())));
            }
        }

        log.info("Sincronización de carpeta Siigo completada: {} procesados, {} saltados sin cambios, {} facturas verificadas",
                processedFiles, skippedFiles, verified);

        return new ScanResult(verified, List.copyOf(errors), skippedFiles);
    }

    private String computeSha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible", ex);
        }
    }

    public record ScanResult(int verifiedDocuments, List<String> errors, int skippedFiles) {
        public ScanResult(int verifiedDocuments, List<String> errors) {
            this(verifiedDocuments, errors, 0);
        }
    }
}
