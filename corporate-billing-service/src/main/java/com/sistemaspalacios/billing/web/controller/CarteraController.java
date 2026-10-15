package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.CarteraSyncService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operaciones sobre el corte de cartera de Siigo (Sprint 13).
 * <p>
 * El saldo de este corte es la fuente de verdad de la cartera, así que estos endpoints
 * permiten forzar la ingesta y revisar las diferencias contra el saldo que el microservicio
 * calcula por su cuenta.
 */
@RestController
@RequestMapping({"/api/v1/cartera", "/cartera"})
public class CarteraController {

    private final CarteraSyncService carteraSyncService;

    public CarteraController(CarteraSyncService carteraSyncService) {
        this.carteraSyncService = Objects.requireNonNull(carteraSyncService, "carteraSyncService cannot be null");
    }

    /**
     * Fuerza la ingesta de los cortes presentes en la carpeta compartida sin esperar el
     * ciclo horario. Los archivos ya ingeridos se omiten por hash.
     */
    @PostMapping("/sincronizar")
    @PreAuthorize("hasAnyAuthority('CARTERA_SYNC', 'ROL_SISTEMA', 'ROLE_SISTEMA')")
    public ResponseEntity<Map<String, Object>> synchronize() {
        CarteraSyncService.CarteraSyncSummary summary = carteraSyncService.processSharedFolderSnapshots();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("archivosEncontrados", summary.filesFound());
        body.put("archivosIngeridos", summary.filesIngested());
        body.put("archivosOmitidosPorDuplicado", summary.filesSkippedAsDuplicate());
        body.put("filasAplicadas", summary.totalAppliedRows());
        body.put("cortes", summary.results().stream().map(this::toCorteMap).toList());
        return ResponseEntity.ok(body);
    }

    /**
     * Diferencias entre el saldo que reporta Siigo y el que el microservicio calcula, según
     * el último corte ingerido.
     * <p>
     * Es una **lectura pura**: no dispara ingesta. Lee la comparación que quedó congelada en
     * cada fila del corte al aplicarla. Para traer un corte nuevo hay que llamar
     * explícitamente a {@code POST /cartera/sincronizar}.
     * <p>
     * Una diferencia positiva significa que Siigo reporta más saldo del que el sistema
     * conoce; negativa, que Siigo reporta menos (por ejemplo un pago registrado
     * directamente en Siigo). Ninguna de las dos es un error por sí misma: el objetivo es
     * que la diferencia sea visible en lugar de silenciosa.
     */
    @GetMapping("/conciliacion")
    @PreAuthorize("hasAuthority('CARTERA_VIEW')")
    public ResponseEntity<Map<String, Object>> reconciliation() {
        return ResponseEntity.ok(carteraSyncService.getLatestReconciliation().toResponse());
    }

    /**
     * Filas del último corte que no se pudieron aplicar: documentos que Siigo reporta y el
     * microservicio no conoce, NIT discrepantes, cortes obsoletos y errores de formato.
     */
    @GetMapping("/pendientes-de-resolver")
    @PreAuthorize("hasAuthority('CARTERA_VIEW')")
    public ResponseEntity<Map<String, Object>> unresolved() {
        return ResponseEntity.ok(carteraSyncService.getLatestUnresolvedRows());
    }

    private Map<String, Object> toCorteMap(@NotNull CarteraSyncService.CarteraSnapshotResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("snapshotId", r.snapshotId());
        map.put("archivo", r.fileName());
        map.put("fechaCorte", r.cutoffDate());
        map.put("filasTotales", r.totalRows());
        map.put("filasAplicadas", r.appliedRows());
        map.put("filasSinFactura", r.unmatchedRows());
        map.put("filasConError", r.errorRows());
        map.put("facturasAusentesDelCorte", r.invoicesAbsentFromSnapshot());
        map.put("diferenciasDeSaldo", r.discrepancies().size());
        return map;
    }
}
