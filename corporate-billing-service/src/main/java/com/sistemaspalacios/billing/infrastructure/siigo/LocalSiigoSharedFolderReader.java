package com.sistemaspalacios.billing.infrastructure.siigo;

import com.sistemaspalacios.billing.service.siigo.SiigoSharedFolderReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Local filesystem / mounted network share implementation of SiigoSharedFolderReader.
 */
@Component
public class LocalSiigoSharedFolderReader implements SiigoSharedFolderReader {

    private static final Logger log = LoggerFactory.getLogger(LocalSiigoSharedFolderReader.class);

    private final String folderPath;
    @Value("${siigo.sync.antiguedad-minima-segundos:60}")
    private long minimumAgeSeconds;

    private boolean ready(File file) {
        return file.isFile() && !file.getName().startsWith("~$")
                && System.currentTimeMillis() - file.lastModified() >= minimumAgeSeconds * 1000;
    }

    public LocalSiigoSharedFolderReader(
            @Value("${siigo.carpeta-compartida.path:./storage/siigo_compartida}") String folderPath
    ) {
        this.folderPath = folderPath;

    }

    @Override
    public boolean isFolderAccessible() {
        try {
            Path path = Paths.get(folderPath);
            return Files.exists(path) && Files.isDirectory(path) && Files.isReadable(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getSharedFolderPath() {
        return Paths.get(folderPath).toAbsolutePath().normalize().toString();
    }

    @Override
    public List<File> listPendingPdfFiles() {
        if (!isFolderAccessible()) {
            log.warn("Siigo shared folder is not accessible: {}", folderPath);
            return Collections.emptyList();
        }

        File folder = new File(folderPath);
        File[] files = folder.listFiles((dir, name) -> ready(new File(dir, name)) && name.toLowerCase().endsWith(".pdf"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
        return list;
    }

    @Override
    public List<File> listConfirmationFlatFiles() {
        if (!isFolderAccessible()) {
            log.warn("Siigo shared folder is not accessible: {}", folderPath);
            return Collections.emptyList();
        }

        // Nota (Sprint 13): ya NO se filtra por palabra clave en el nombre. El filtro anterior
        // exigía que el nombre contuviera "consecutivo", "confirmacion", "siigo" o "lote", de
        // modo que bastaba con que contabilidad renombrara el archivo para que dejara de
        // procesarse en silencio. Se filtra solo por extensión y el parser decide si el
        // contenido le sirve.
        File folder = new File(folderPath);
        File[] files = folder.listFiles((dir, name) -> {
            if (!ready(new File(dir, name))) return false;
            String lower = name.toLowerCase();
            return lower.endsWith(".txt") || lower.endsWith(".csv")
                    || lower.endsWith(".dat") || lower.endsWith(".json");
        });

        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
        return list;
    }

    @Override
    public List<File> listCarteraSpreadsheets() {
        if (!isFolderAccessible()) {
            log.warn("Siigo shared folder is not accessible: {}", folderPath);
            return Collections.emptyList();
        }

        File folder = new File(folderPath);
        File[] files = folder.listFiles((dir, name) -> {
            if (!ready(new File(dir, name))) return false;
            String lower = name.toLowerCase();
            // Se excluyen los archivos temporales que Excel crea mientras alguien tiene el
            // libro abierto (~$saldos_de_cartera.xlsx): son ilegibles y no son el corte.
            if (lower.startsWith("~$")) {
                return false;
            }
            return lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm");
        });

        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
        return list;
    }
}
