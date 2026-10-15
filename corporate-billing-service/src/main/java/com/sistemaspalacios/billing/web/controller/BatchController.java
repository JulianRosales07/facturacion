package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.service.BatchService;
import com.sistemaspalacios.billing.service.SiigoPdfImporterService;
import com.sistemaspalacios.billing.service.siigo.SiigoConfirmationRecord;
import com.sistemaspalacios.billing.web.dto.GenerarLoteRequest;
import com.sistemaspalacios.billing.web.dto.LoteResponseDto;
import com.sistemaspalacios.billing.web.dto.ProcesarCarpetaResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/lotes", "/lotes"})
public class BatchController {

    private final BatchService batchService;
    private final SiigoPdfImporterService pdfImporterService;

    public BatchController(BatchService batchService, SiigoPdfImporterService pdfImporterService) {
        this.batchService = batchService;
        this.pdfImporterService = pdfImporterService;
    }

    @PostMapping("/generar")
    @PreAuthorize("hasAuthority('BATCHES_MANAGE')")
    public ResponseEntity<LoteResponseDto> generarLote(@Valid @RequestBody GenerarLoteRequest request) {
        ExportBatch batch = batchService.generateBatch(
                request.periodo(),
                request.tipoFacturacion(),
                request.tipoCorte()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(LoteResponseDto.fromEntity(batch));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BATCHES_VIEW')")
    public ResponseEntity<LoteResponseDto> obtenerLote(@PathVariable UUID id) {
        return batchService.getBatchById(id)
                .map(b -> ResponseEntity.ok(LoteResponseDto.fromEntity(b)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BATCHES_VIEW')")
    public ResponseEntity<List<LoteResponseDto>> listarLotes() {
        List<LoteResponseDto> list = batchService.getAllBatches().stream()
                .map(LoteResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}/archivo")
    @PreAuthorize("hasAuthority('BATCHES_VIEW')")
    public ResponseEntity<byte[]> descargarArchivoLote(@PathVariable UUID id) {
        ExportBatch batch = batchService.getBatchById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lote no encontrado: " + id));

        byte[] fileBytes = batchService.getBatchFile(id);

        String fileName = batch.getFileName() != null ? batch.getFileName() : "LOTE_" + id + ".txt";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(fileBytes);
    }

    @PostMapping("/{id}/confirmar")
    @PreAuthorize("hasAuthority('BATCHES_MANAGE')")
    public ResponseEntity<LoteResponseDto> confirmarLote(
            @PathVariable UUID id,
            @RequestBody(required = false) List<SiigoConfirmationRecord> confirmaciones
    ) {
        ExportBatch confirmed;
        if (confirmaciones != null && !confirmaciones.isEmpty()) {
            confirmed = batchService.confirmBatch(id, confirmaciones);
        } else {
            // Confirm from shared folder on server
            confirmed = batchService.confirmBatchFromSharedFolder(id);
        }
        return ResponseEntity.ok(LoteResponseDto.fromEntity(confirmed));
    }

    @PostMapping("/procesar-carpeta")
    @PreAuthorize("hasAuthority('BATCHES_MANAGE')")
    public ResponseEntity<ProcesarCarpetaResponseDto> procesarCarpetaCompartida() {
        SiigoPdfImporterService.PdfProcessingSummary summary = pdfImporterService.processSharedFolderPdfs();
        return ResponseEntity.ok(ProcesarCarpetaResponseDto.fromSummary(summary));
    }
}
