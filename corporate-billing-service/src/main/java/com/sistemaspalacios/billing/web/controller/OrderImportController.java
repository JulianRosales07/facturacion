package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.service.OrderImportService;
import com.sistemaspalacios.billing.web.dto.FolderImportRequestDto;
import com.sistemaspalacios.billing.service.dto.ImportHistoryDto;
import com.sistemaspalacios.billing.service.dto.ImportResultDto;
import com.sistemaspalacios.billing.service.dto.NitRecordDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/importaciones", "/importaciones"})
public class OrderImportController {

    private final OrderImportService orderImportService;

    public OrderImportController(OrderImportService orderImportService) {
        this.orderImportService = orderImportService;
    }

    /**
     * Import a single Excel (.xlsx, .xls) or flat file (.csv, .txt).
     */
    @PostMapping(value = "/archivo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('IMPORTS_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<ImportResultDto> importFile(
            @RequestParam("archivo") MultipartFile file,
            @RequestParam(value = "periodo", required = false) String periodo,
            @RequestParam(value = "origen", required = false) OrderOrigin origen,
            @RequestParam(value = "metodo", required = false, defaultValue = "EXCEL") String metodo
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new ImportResultDto(
                    UUID.randomUUID(),
                    "archivo_vacio",
                    metodo,
                    (origen != null ? origen : OrderOrigin.SPE).name(),
                    "ERROR",
                    0,
                    1,
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    List.of(),
                    List.of("El archivo enviado está vacío.")
            ));
        }

        try (InputStream is = file.getInputStream()) {
            ImportResultDto result = orderImportService.importFile(
                    file.getOriginalFilename(),
                    is,
                    periodo,
                    origen,
                    metodo
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ImportResultDto(
                    UUID.randomUUID(),
                    file.getOriginalFilename(),
                    metodo,
                    (origen != null ? origen : OrderOrigin.SPE).name(),
                    "ERROR",
                    0,
                    1,
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    List.of(),
                    List.of("Error al procesar archivo: " + e.getMessage())
            ));
        }
    }

    /**
     * Process multiple files selected from a folder via the frontend.
     */
    @PostMapping(value = "/carpeta/archivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('IMPORTS_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<ImportResultDto> importFolderFiles(
            @RequestParam("archivos") MultipartFile[] files,
            @RequestParam(value = "periodo", required = false) String periodo,
            @RequestParam(value = "origen", required = false) OrderOrigin origen
    ) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().build();
        }

        int totalAll = 0;
        int errorAll = 0;
        List<NitRecordDto> allSummaries = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        UUID lastId = null;

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            try (InputStream is = file.getInputStream()) {
                ImportResultDto res = orderImportService.importFile(
                        file.getOriginalFilename(),
                        is,
                        periodo,
                        origen,
                        "CARPETA_LOCAL"
                );
                totalAll += res.totalRecords();
                errorAll += res.errorRecords();
                allSummaries.addAll(res.records());
                messages.addAll(res.messages());
                lastId = res.id();
            } catch (Exception e) {
                messages.add("Error procesando " + file.getOriginalFilename() + ": " + e.getMessage());
                errorAll++;
            }
        }

        ImportResultDto consolidated = new ImportResultDto(
                lastId != null ? lastId : UUID.randomUUID(),
                "Carpeta (" + files.length + " archivos)",
                "Consumo de carpeta",
                (origen != null ? origen : OrderOrigin.SPE).name(),
                errorAll > 0 && totalAll == 0 ? "ERROR" : "PROCESADO",
                totalAll,
                errorAll,
                java.time.Instant.now(),
                java.time.Instant.now(),
                allSummaries,
                messages
        );

        return ResponseEntity.ok(consolidated);
    }

    /**
     * Inspect server folder configuration, accessibility, and pending files count.
     */
    @GetMapping("/carpeta/config")
    @PreAuthorize("hasAnyAuthority('IMPORTS_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<com.sistemaspalacios.billing.service.dto.FolderConfigDto> getFolderConfig(
            @RequestParam(value = "path", required = false) String path
    ) {
        com.sistemaspalacios.billing.service.dto.FolderConfigDto config = orderImportService.getFolderConfig(path);
        return ResponseEntity.ok(config);
    }

    /**
     * Process a shared server/network folder.
     */
    @PostMapping("/carpeta")
    @PreAuthorize("hasAnyAuthority('IMPORTS_EXECUTE', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<ImportResultDto> importFolder(
            @RequestBody(required = false) FolderImportRequestDto request
    ) {
        String path = request != null ? request.folderPath() : null;
        String period = request != null ? request.periodCode() : null;
        OrderOrigin origin = request != null ? request.origin() : null;

        ImportResultDto result = orderImportService.importFromFolder(path, period, origin);
        return ResponseEntity.ok(result);
    }

    /**
     * Get import history from database.
     */
    @GetMapping("/historial")
    @PreAuthorize("hasAnyAuthority('IMPORTS_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<List<ImportHistoryDto>> getHistory() {
        List<ImportHistoryDto> history = orderImportService.getHistory();
        return ResponseEntity.ok(history);
    }

    /**
     * Get current client/site records from database.
     */
    @GetMapping("/registros-recientes")
    @PreAuthorize("hasAnyAuthority('IMPORTS_VIEW', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<List<NitRecordDto>> getRecentRecords() {
        List<NitRecordDto> records = orderImportService.getRecentRecords();
        return ResponseEntity.ok(records);
    }
}
