package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.service.NovedadesFacturaEnPreparacionTestSupport.Fila;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dos procesos incorporan novedades a la misma factura al mismo tiempo. Sin
 * {@code @Transactional} en la prueba: cada llamada confirma su propia transacción, como en
 * producción. Los datos creados se eliminan al terminar.
 */
@SpringBootTest
@ActiveProfiles("postgres")
class NovedadesConcurrenciaIntegrationTest {

    private static final String NIT = "907702001";
    private static final String NOV = "2026-11";
    private static final String ARCHIVO = "prueba-concurrencia-novedades.xlsx";

    @Autowired private OrderImportService importService;
    @Autowired private BillingService billingService;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private RepositorioCliente repositorioCliente;
    @Autowired private RepositorioPeriodo repositorioPeriodo;
    @Autowired private JdbcTemplate jdbc;

    private boolean periodoExistiaAntes;

    @BeforeEach
    void recordarPeriodo() {
        periodoExistiaAntes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM periodo_facturacion WHERE codigo = ?", Integer.class, NOV) > 0;
    }

    @AfterEach
    void limpiar() {
        String sedes = "SELECT s.id FROM sede_suscripcion s JOIN cliente_corporativo c ON c.id = s.cliente_corporativo_id WHERE c.nit = ?";
        String facturas = "SELECT f.id FROM factura f JOIN cliente_corporativo c ON c.id = f.cliente_corporativo_id WHERE c.nit = ?";
        jdbc.update("DELETE FROM item_facturable WHERE factura_id IN (" + facturas + ")", NIT);
        jdbc.update("DELETE FROM novedad_orden WHERE sede_suscripcion_id IN (" + sedes + ")", NIT);
        jdbc.update("DELETE FROM factura WHERE id IN (" + facturas + ")", NIT);
        jdbc.update("DELETE FROM archivo_importado_linea WHERE sede_suscripcion_id IN (" + sedes + ")", NIT);
        jdbc.update("DELETE FROM sede_suscripcion WHERE id IN (" + sedes + ")", NIT);
        jdbc.update("DELETE FROM cliente_corporativo WHERE nit = ?", NIT);
        String archivos = "SELECT id FROM archivo_importado WHERE nombre_archivo = ?";
        jdbc.update("DELETE FROM archivo_importado_linea WHERE archivo_importado_id IN (" + archivos + ")", ARCHIVO);
        jdbc.update("DELETE FROM archivo_importado WHERE nombre_archivo = ?", ARCHIVO);
        if (!periodoExistiaAntes) {
            jdbc.update("DELETE FROM periodo_facturacion p WHERE p.codigo = ? "
                    + "AND NOT EXISTS (SELECT 1 FROM factura f WHERE f.periodo_facturacion_id = p.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM novedad_orden n WHERE n.periodo_facturacion_id = p.id)", NOV);
        }
    }

    private void importar(Fila fila) throws Exception {
        importService.importFile(ARCHIVO, NovedadesFacturaEnPreparacionTestSupport.excel(List.of(fila)), NOV, OrderOrigin.SPE, "EXCEL");
    }

    @Test
    @DisplayName("Dos recálculos simultáneos de la misma factura: se ejecutan uno tras otro y el resultado es consistente")
    void recalculosConcurrentesNoCorrompenLaFactura() throws Exception {
        importar(Fila.sede(NIT, "CON-01-A", 500_000));
        Invoice factura = billingService.generateConsolidatedInvoice(NOV, BillingType.F1, BillingCutType.ADVANCE, NIT);
        importar(Fila.novedad(NIT, "CON-01-A", "CAMBIO_PLAN", LocalDate.of(2026, 11, 16), 800_000));

        UUID clientId = repositorioCliente.findByNit(NIT).orElseThrow().getId();
        UUID periodId = repositorioPeriodo.findByCode(NOV).orElseThrow().getId();

        int hilos = 4;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> resultados = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            resultados.add(pool.submit(() -> {
                salida.await();
                return billingService.refreshEditableInvoice(clientId, periodId);
            }));
        }
        salida.countDown();
        for (Future<?> r : resultados) {
            r.get(60, TimeUnit.SECONDS); // falla la prueba si algún recálculo lanzó una excepción
        }
        pool.shutdown();

        Invoice final_ = repositorioFactura.findById(factura.getId()).orElseThrow();
        assertEquals(2, final_.getItems().size(), "Cargo fijo + incremento, sin conceptos duplicados ni perdidos");
        assertEquals(Money.of(773_500), final_.getTotalAmount());
        assertEquals(1, repositorioFactura.findByClientId(clientId).size(), "Ninguna factura adicional");
    }
}
