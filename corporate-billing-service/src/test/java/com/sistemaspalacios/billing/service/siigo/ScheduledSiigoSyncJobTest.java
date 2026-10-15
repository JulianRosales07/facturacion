package com.sistemaspalacios.billing.service.siigo;

import com.sistemaspalacios.billing.domain.model.SyncStatusEntity;
import com.sistemaspalacios.billing.repository.RepositorioEstadoSincronizacion;
import com.sistemaspalacios.billing.repository.RepositorioLote;
import com.sistemaspalacios.billing.service.BatchService;
import com.sistemaspalacios.billing.service.SiigoPdfImporterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledSiigoSyncJobTest {

    @Mock
    private BatchService batchService;

    @Mock
    private SiigoPdfImporterService siigoPdfImporterService;

    @Mock
    private RepositorioLote repositorioLote;

    @Mock
    private RepositorioEstadoSincronizacion repositorioEstadoSincronizacion;

    private ScheduledSiigoSyncJob syncJob;

    @BeforeEach
    void setUp() {
        syncJob = new ScheduledSiigoSyncJob(
                batchService,
                siigoPdfImporterService,
                repositorioLote,
                repositorioEstadoSincronizacion
        );
        when(repositorioEstadoSincronizacion.save(any(SyncStatusEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("HU-56: Ejecución programada exitosa actualiza métricas y persiste SyncStatusEntity")
    void testSuccessfulScheduledSync() {
        when(repositorioLote.findByStatus(any())).thenReturn(List.of());
        when(siigoPdfImporterService.processSharedFolderPdfs())
                .thenReturn(new SiigoPdfImporterService.PdfProcessingSummary(0, 0, 0, List.of(), List.of()));

        SyncStatusEntity status = syncJob.runScheduledSync();

        assertNotNull(status);
        assertEquals("EXITOSA", status.getEstado());
        assertEquals("PROGRAMADA", status.getTipoDisparador());
        assertNotNull(status.getFechaFin());
        assertTrue(status.getDuracionMs() >= 0);

        verify(repositorioEstadoSincronizacion, atLeastOnce()).save(any(SyncStatusEntity.class));
    }

    @Test
    @DisplayName("HU-56: Ejecución manual on-demand funciona y marca tipo MANUAL")
    void testManualTriggerSync() {
        when(repositorioLote.findByStatus(any())).thenReturn(List.of());
        when(siigoPdfImporterService.processSharedFolderPdfs())
                .thenReturn(new SiigoPdfImporterService.PdfProcessingSummary(0, 0, 0, List.of(), List.of()));

        SyncStatusEntity status = syncJob.triggerManualSync();

        assertNotNull(status);
        assertEquals("EXITOSA", status.getEstado());
        assertEquals("MANUAL", status.getTipoDisparador());
    }

    @Test
    @DisplayName("HU-56: Tolerancia a fallos — Si el importador de PDFs falla, no tumba el job y guarda el error")
    void testFaultToleranceOnPdfFailure() {
        when(repositorioLote.findByStatus(any())).thenReturn(List.of());
        when(siigoPdfImporterService.processSharedFolderPdfs())
                .thenThrow(new RuntimeException("Carpeta compartida inaccesible"));

        SyncStatusEntity status = syncJob.runScheduledSync();

        assertNotNull(status);
        assertEquals("FALLIDA", status.getEstado());
        assertNotNull(status.getDetalleErrores());
        assertTrue(status.getDetalleErrores().contains("Carpeta compartida inaccesible"));
        verify(repositorioEstadoSincronizacion, atLeastOnce()).save(any(SyncStatusEntity.class));
    }
}
