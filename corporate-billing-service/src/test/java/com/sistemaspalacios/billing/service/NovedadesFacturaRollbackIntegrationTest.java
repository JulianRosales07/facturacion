package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.service.NovedadesFacturaEnPreparacionTestSupport.Fila;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 9. Un error al incorporar la novedad revierte toda la importación.
 * <p>
 * Sin {@code @Transactional} en la prueba: cada importación confirma o revierte su propia
 * transacción, como en producción. Los datos creados se eliminan al terminar.
 */
@SpringBootTest
@ActiveProfiles("postgres")
class NovedadesFacturaRollbackIntegrationTest {

    private static final String NIT = "907700009";
    private static final String NOV = "2026-11";
    private static final String ARCHIVO = "prueba-reversion-novedades.xlsx";

    @Autowired private OrderImportService importService;
    @Autowired private BillingService billingService;
    @Autowired private RepositorioFactura repositorioFactura;
    @Autowired private JdbcTemplate jdbc;

    /** El periodo solo se borra al final si esta prueba fue la que lo creó. */
    private boolean periodoExistiaAntes;

    @org.junit.jupiter.api.BeforeEach
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
        jdbc.update("DELETE FROM cuota_instalacion WHERE sede_suscripcion_id IN (" + sedes + ")", NIT);
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
        importService.importFile(ARCHIVO, NovedadesFacturaEnPreparacionTestSupport.excel(List.of(fila)),
                NOV, OrderOrigin.SPE, "EXCEL");
    }

    @Test
    @DisplayName("9. Si la factura no puede recalcularse, no queda ni la novedad ni el cambio en la factura")
    void errorAlRegistrarElConceptoRevierteLaTransaccion() throws Exception {
        importar(Fila.sede(NIT, "ACU-09-A", 500_000));
        Invoice factura = billingService.generateConsolidatedInvoice(NOV, BillingType.F1, BillingCutType.ADVANCE, NIT);

        // Una baja desde el día 1 dejaría la factura sin conceptos: el dominio la rechaza.
        Exception error = assertThrows(IllegalStateException.class, () -> importar(
                Fila.novedad(NIT, "ACU-09-A", "BAJA", LocalDate.of(2026, 11, 1), 450_000)));
        assertTrue(error.getMessage().contains("sin conceptos"), error.getMessage());

        Invoice intacta = repositorioFactura.findById(factura.getId()).orElseThrow();
        assertEquals(1, intacta.getItems().size(), "Los conceptos de la factura no cambiaron");
        assertEquals(Money.of(595_000), intacta.getTotalAmount());

        Integer novedades = jdbc.queryForObject(
                "SELECT COUNT(*) FROM novedad_orden n JOIN sede_suscripcion s ON s.id = n.sede_suscripcion_id "
                        + "JOIN cliente_corporativo c ON c.id = s.cliente_corporativo_id WHERE c.nit = ?", Integer.class, NIT);
        assertEquals(0, novedades, "La novedad del archivo fallido no quedó registrada");

        BigDecimal valorSede = jdbc.queryForObject(
                "SELECT s.valor_mensual FROM sede_suscripcion s JOIN cliente_corporativo c ON c.id = s.cliente_corporativo_id "
                        + "WHERE c.nit = ?", BigDecimal.class, NIT);
        assertEquals(0, valorSede.compareTo(BigDecimal.valueOf(500_000)),
                "El valor mensual que traía el archivo fallido (450.000) también se revirtió");
    }
}
