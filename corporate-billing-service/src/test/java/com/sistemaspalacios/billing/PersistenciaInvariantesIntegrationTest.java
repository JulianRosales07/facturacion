package com.sistemaspalacios.billing;

import com.sistemaspalacios.billing.domain.model.BillableItemType;
import com.sistemaspalacios.billing.domain.model.BillingCutType;
import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.ExportBatch;
import com.sistemaspalacios.billing.domain.model.ExportBatchStatus;
import com.sistemaspalacios.billing.domain.model.Invoice;
import com.sistemaspalacios.billing.domain.model.InvoiceItem;
import com.sistemaspalacios.billing.domain.model.InvoiceStatus;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.domain.model.ServiceClassification;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.domain.vo.TaxProfile;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioFactura;
import com.sistemaspalacios.billing.repository.RepositorioLote;
import com.sistemaspalacios.billing.repository.RepositorioNovedad;
import com.sistemaspalacios.billing.repository.RepositorioPeriodo;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import com.sistemaspalacios.billing.domain.model.ExtraFieldDataType;
import com.sistemaspalacios.billing.domain.model.ExtraFieldDefinition;
import com.sistemaspalacios.billing.domain.model.InvoiceExtraField;
import com.sistemaspalacios.billing.repository.RepositorioCampoExtraFactura;
import com.sistemaspalacios.billing.repository.RepositorioDefinicionCampoExtra;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
class PersistenciaInvariantesIntegrationTest {

    @Autowired
    private RepositorioCliente repositorioCliente;

    @Autowired
    private RepositorioSede repositorioSede;

    @Autowired
    private RepositorioPeriodo repositorioPeriodo;

    @Autowired
    private RepositorioNovedad repositorioNovedad;

    @Autowired
    private RepositorioFactura repositorioFactura;

    @Autowired
    private RepositorioLote repositorioLote;

    @Autowired
    private RepositorioVigenciaConcepto repositorioVigenciaConcepto;

    @Autowired
    private RepositorioDefinicionCampoExtra repositorioDefinicionCampoExtra;

    @Autowired
    private RepositorioCampoExtraFactura repositorioCampoExtraFactura;

    private BillingPeriod obtenerOCrearPeriodo(String codigo, BillingCutType cutType, LocalDate inicio, LocalDate fin) {
        return repositorioPeriodo.findByCode(codigo).orElseGet(() ->
                repositorioPeriodo.saveAndFlush(new BillingPeriod(UUID.randomUUID(), codigo, cutType, inicio, fin, true))
        );
    }

    private String generarNitUnico() {
        return "900" + (System.currentTimeMillis() % 100000000);
    }

    @Test
    @DisplayName("S2-01: Guardar y leer agregado de Factura completo con ítems y Money")
    @Transactional
    void testGuardarYLeerAgregadoFactura() {
        CorporateClient cliente = new CorporateClient(
                UUID.randomUUID(),
                generarNitUnico(),
                "EMPRESA PRUEBA AGREGADO S.A.S.",
                "Calle 100 # 20-30",
                "factura@empresaagregado.com",
                "3101234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
        cliente = repositorioCliente.saveAndFlush(cliente);

        BillingPeriod periodo = obtenerOCrearPeriodo("2026-11", BillingCutType.ADVANCE, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        SubscriptionSite sede = new SubscriptionSite(
                UUID.randomUUID(),
                cliente.getId(),
                "ORD-" + UUID.randomUUID().toString().substring(0, 8),
                OrderOrigin.SPE,
                "NIC-9999",
                "Sede Principal Pasto",
                "Calle 100 # 20-30",
                Money.of(2_500_000),
                LocalDate.of(2026, 11, 1),
                true
        );
        sede = repositorioSede.saveAndFlush(sede);

        Invoice factura = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 12, 5)
        );

        InvoiceItem item1 = new InvoiceItem(
                UUID.randomUUID(),
                factura.getId(),
                BillableItemType.CORPORATE_SERVICE,
                sede.getId(),
                "SEDE.INT.FIB.DEDICADO.100M GR 01-30 NOV 2026",
                BigDecimal.ONE,
                Money.of(2_500_000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        );

        factura.addItem(item1);
        factura.consolidate();

        Invoice guardada = repositorioFactura.saveAndFlush(factura);
        assertNotNull(guardada.getId());

        Optional<Invoice> encontradaOpt = repositorioFactura.findById(guardada.getId());
        assertTrue(encontradaOpt.isPresent());
        Invoice encontrada = encontradaOpt.get();

        assertEquals(cliente.getNit(), encontrada.getClientNit());
        assertEquals(cliente.getBusinessName(), encontrada.getClientBusinessName());
        assertEquals(Money.of(2_500_000), encontrada.getSubtotalAmount());
        assertEquals(Money.of(475_000), encontrada.getIvaAmount());
        assertEquals(Money.of(2_975_000), encontrada.getTotalAmount());
        assertEquals(InvoiceStatus.CONSOLIDATED, encontrada.getStatus());
        assertEquals(1, encontrada.getItems().size());
        assertEquals("SEDE.INT.FIB.DEDICADO.100M GR 01-30 NOV 2026", encontrada.getItems().get(0).getDescription());
    }

    @Test
    @DisplayName("HU-08: El índice único parcial uq_factura_cliente_periodo_activa rechaza 2 facturas activas para el mismo cliente y periodo")
    void testIndiceUnicoParcialRechazaFacturaDuplicada() {
        CorporateClient cliente = new CorporateClient(
                UUID.randomUUID(),
                generarNitUnico(),
                "CLIENTE UNICIDAD S.A.S.",
                "Cra 5 # 10-20",
                "unicidad@empresa.com",
                "3123456789",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
        cliente = repositorioCliente.saveAndFlush(cliente);

        BillingPeriod periodo = obtenerOCrearPeriodo("2026-12", BillingCutType.ADVANCE, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));

        Invoice factura1 = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2026, 12, 5),
                LocalDate.of(2027, 1, 5)
        );
        factura1.addItem(new InvoiceItem(
                UUID.randomUUID(),
                factura1.getId(),
                BillableItemType.CORPORATE_SERVICE,
                null,
                "Item 1",
                BigDecimal.ONE,
                Money.of(100_000),
                BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        ));
        repositorioFactura.saveAndFlush(factura1);

        Invoice factura2 = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2026, 12, 5),
                LocalDate.of(2027, 1, 5)
        );
        factura2.addItem(new InvoiceItem(
                UUID.randomUUID(),
                factura2.getId(),
                BillableItemType.CORPORATE_SERVICE,
                null,
                "Item 2 duplicado",
                BigDecimal.ONE,
                Money.of(200_000),
                BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        ));

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> {
            repositorioFactura.saveAndFlush(factura2);
        }, "Debe violar el índice único parcial uq_factura_cliente_periodo_activa al intentar guardar 2 facturas activas");

        assertTrue(
                ex.getMessage().contains("uq_factura_cliente_periodo_activa") ||
                (ex.getCause() != null && ex.getCause().getMessage().contains("uq_factura_cliente_periodo_activa")),
                "La violación debe provenir de uq_factura_cliente_periodo_activa"
        );
    }

    @Test
    @DisplayName("HU-08: Anular una factura permite crear una nueva factura activa para el mismo cliente y periodo (refacturación)")
    void testRefacturacionPermitidaTrasAnulacion() {
        CorporateClient cliente = new CorporateClient(
                UUID.randomUUID(),
                generarNitUnico(),
                "CLIENTE REFACTURACION S.A.S.",
                "Cra 15 # 40-50",
                "refacturacion@empresa.com",
                "3134567890",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
        cliente = repositorioCliente.saveAndFlush(cliente);

        BillingPeriod periodo = obtenerOCrearPeriodo("2027-01", BillingCutType.ADVANCE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31));

        // 1. Guardar factura 1 activa
        Invoice factura1 = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2027, 1, 5),
                LocalDate.of(2027, 2, 5)
        );
        factura1.addItem(new InvoiceItem(
                UUID.randomUUID(),
                factura1.getId(),
                BillableItemType.CORPORATE_SERVICE,
                null,
                "Item factura original",
                BigDecimal.ONE,
                Money.of(500_000),
                BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        ));
        repositorioFactura.saveAndFlush(factura1);

        // 2. Anular factura 1 (estado = ANULADA)
        factura1.voidInvoice("Error en valor acordado, se procede a refacturar");
        repositorioFactura.saveAndFlush(factura1);

        // 3. Crear factura 2 (nueva activa para el mismo cliente y periodo)
        Invoice factura2 = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2027, 1, 6),
                LocalDate.of(2027, 2, 6)
        );
        factura2.addItem(new InvoiceItem(
                UUID.randomUUID(),
                factura2.getId(),
                BillableItemType.CORPORATE_SERVICE,
                null,
                "Item factura corregida",
                BigDecimal.ONE,
                Money.of(450_000),
                BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        ));

        assertDoesNotThrow(() -> {
            Invoice guardada = repositorioFactura.saveAndFlush(factura2);
            assertNotNull(guardada.getId());
        });
    }

    @Test
    @DisplayName("HU-09: Snapshot fiscal inmutable en cabecera no cambia al editar datos del cliente")
    @Transactional
    void testSnapshotFiscalInmutableNoCambiaAlEditarCliente() {
        String nitOriginal = generarNitUnico();
        String razonSocialOriginal = "DISTRIBUIDORA ANDINA SAS";
        String direccionOriginal = "Av Panamericana # 12-34";
        String emailOriginal = "facturas@andina.com";

        CorporateClient cliente = new CorporateClient(
                UUID.randomUUID(),
                nitOriginal,
                razonSocialOriginal,
                direccionOriginal,
                emailOriginal,
                "7234567",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
        cliente = repositorioCliente.saveAndFlush(cliente);

        BillingPeriod periodo = obtenerOCrearPeriodo("2027-02", BillingCutType.ADVANCE, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));

        Invoice factura = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2027, 2, 5),
                LocalDate.of(2027, 3, 5)
        );
        factura.addItem(new InvoiceItem(
                UUID.randomUUID(),
                factura.getId(),
                BillableItemType.CORPORATE_SERVICE,
                null,
                "Servicio Dedicado",
                BigDecimal.ONE,
                Money.of(1_000_000),
                BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        ));
        Invoice facturaGuardada = repositorioFactura.saveAndFlush(factura);

        // Modificar datos fiscales del cliente
        cliente.setNit("900" + (System.currentTimeMillis() % 100000000));
        cliente.setBusinessName("NUEVA RAZON SOCIAL ANDINA GLOBAL S.A.");
        cliente.setTaxAddress("Cra 27 # 45-67 Bogotá");
        cliente.setBillingEmail("nuevo_email@andinaglobal.com");
        repositorioCliente.saveAndFlush(cliente);

        // Recargar la factura emitida previamente
        Invoice facturaRecargada = repositorioFactura.findById(facturaGuardada.getId()).orElseThrow();

        assertEquals(nitOriginal, facturaRecargada.getClientNit(), "El NIT en la factura no debe cambiar");
        assertEquals(razonSocialOriginal, facturaRecargada.getClientBusinessName(), "La razón social en la factura no debe cambiar");
        assertEquals(direccionOriginal, facturaRecargada.getClientTaxAddress(), "La dirección fiscal en la factura no debe cambiar");
        assertEquals(emailOriginal, facturaRecargada.getClientBillingEmail(), "El email en la factura no debe cambiar");
    }

    @Test
    @DisplayName("HU-29/RF-29: Inactivación conserva histórico y permite consultas activas vs históricas")
    @Transactional
    void testInactivacionConservaHistorico() {
        CorporateClient cliente = new CorporateClient(
                UUID.randomUUID(),
                generarNitUnico(),
                "CLIENTE PARA INACTIVAR SAS",
                "Calle 5 # 10-20",
                "contacto@inactivar.com",
                "3159998877",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
        cliente = repositorioCliente.saveAndFlush(cliente);

        SubscriptionSite sede = new SubscriptionSite(
                UUID.randomUUID(),
                cliente.getId(),
                "ORD-" + UUID.randomUUID().toString().substring(0, 8),
                OrderOrigin.MEGA_RED,
                "NIC-1111",
                "Sede Norte",
                "Calle 5 # 10-20",
                Money.of(800_000),
                LocalDate.of(2026, 9, 1),
                true
        );
        sede = repositorioSede.saveAndFlush(sede);

        assertTrue(cliente.isActive());
        assertTrue(sede.isActive());

        // Inactivar cliente y sede (soft delete / cambio de estado)
        cliente.deactivate();
        sede.deactivate();

        repositorioCliente.saveAndFlush(cliente);
        repositorioSede.saveAndFlush(sede);

        final UUID clienteId = cliente.getId();
        List<CorporateClient> clientesActivos = repositorioCliente.findByStatus(RecordStatus.ACTIVO);
        assertFalse(clientesActivos.stream().anyMatch(c -> c.getId().equals(clienteId)));

        List<SubscriptionSite> sedesActivas = repositorioSede.findByClientIdAndStatus(cliente.getId(), RecordStatus.ACTIVO);
        assertTrue(sedesActivas.isEmpty());

        Optional<CorporateClient> clienteRecuperado = repositorioCliente.findById(cliente.getId());
        assertTrue(clienteRecuperado.isPresent());
        assertEquals(RecordStatus.INACTIVO, clienteRecuperado.get().getStatus());

        Optional<SubscriptionSite> sedeRecuperada = repositorioSede.findById(sede.getId());
        assertTrue(sedeRecuperada.isPresent());
        assertEquals(RecordStatus.INACTIVO, sedeRecuperada.get().getStatus());
        assertEquals("NIC-1111", sedeRecuperada.get().getNic());
    }

    @Test
    @DisplayName("S2-02: Persistencia de novedades, lotes y vigencias de configuración tributaria")
    @Transactional
    void testPersistenciaOtrosAgregados() {
        BillingPeriod periodo = obtenerOCrearPeriodo("2027-03", BillingCutType.ARREARS, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31));

        ExportBatch lote = new ExportBatch(
                UUID.randomUUID(),
                periodo.getId(),
                BillingType.F1,
                "ADELANTADO",
                "LOTE_202703_F1.txt",
                15,
                ExportBatchStatus.GENERADO
        );
        lote = repositorioLote.saveAndFlush(lote);
        assertNotNull(lote.getId());

        lote.confirm();
        repositorioLote.saveAndFlush(lote);
        ExportBatch loteConfirmado = repositorioLote.findById(lote.getId()).orElseThrow();
        assertEquals(ExportBatchStatus.CONFIRMADO, loteConfirmado.getStatus());
        assertNotNull(loteConfirmado.getConfirmedAt());

        TaxConceptValidity vigencia = new TaxConceptValidity(
                UUID.randomUUID(),
                TaxConceptCode.RETEFUENTE,
                ServiceClassification.INTERNET,
                null,
                Money.of(150_000),
                new BigDecimal("4.00"),
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31),
                true
        );
        vigencia = repositorioVigenciaConcepto.saveAndFlush(vigencia);
        assertNotNull(vigencia.getId());

        List<TaxConceptValidity> vigenciasActivas = repositorioVigenciaConcepto.findByConceptCodeAndDate(
                TaxConceptCode.RETEFUENTE,
                LocalDate.of(2027, 6, 15)
        );
        final UUID vigenciaId = vigencia.getId();
        assertFalse(vigenciasActivas.isEmpty());
        assertTrue(vigenciasActivas.stream().anyMatch(v -> v.getId().equals(vigenciaId)));
    }

    @Test
    @Transactional
    @DisplayName("S2-09: Persistencia y validación de campos extra dinámicos (EAV) en factura")
    void testPersistenciaCamposExtraFactura() {
        String keyOc = "orden_compra_" + (System.currentTimeMillis() % 100000);
        String keyCc = "centro_costo_" + (System.currentTimeMillis() % 100000);

        ExtraFieldDefinition defTexto = new ExtraFieldDefinition(
                UUID.randomUUID(),
                keyOc,
                "Orden de Compra",
                ExtraFieldDataType.TEXTO,
                true
        );
        repositorioDefinicionCampoExtra.saveAndFlush(defTexto);

        ExtraFieldDefinition defNumero = new ExtraFieldDefinition(
                UUID.randomUUID(),
                keyCc,
                "Centro de Costo Dinámico",
                ExtraFieldDataType.NUMERO,
                false
        );
        repositorioDefinicionCampoExtra.saveAndFlush(defNumero);

        // Validación de tipo de dato
        assertThrows(IllegalArgumentException.class, () -> defNumero.validateValue("NO_ES_NUMERO"));

        // Crear cliente, periodo y factura
        BillingPeriod periodo = obtenerOCrearPeriodo("2027-04", BillingCutType.ADVANCE, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 30));
        CorporateClient cliente = new CorporateClient(
                UUID.randomUUID(),
                generarNitUnico(),
                "Cliente Extra S.A.S.",
                "Cra 1 # 2-3",
                "facturacion@clienteextra.com",
                "3001112233",
                (short) 1,
                (short) 1,
                BillingType.F1,
                false,
                "Pasto",
                TaxProfile.withoutWithholding("Pasto")
        );
        cliente = repositorioCliente.saveAndFlush(cliente);

        Invoice factura = new Invoice(
                UUID.randomUUID(),
                cliente.getId(),
                periodo.getId(),
                BillingType.F1,
                cliente,
                LocalDate.of(2027, 4, 1),
                LocalDate.of(2027, 4, 30)
        );

        factura.addItem(new InvoiceItem(
                UUID.randomUUID(),
                factura.getId(),
                BillableItemType.CORPORATE_SERVICE,
                null,
                "Servicio de Internet Dedicado",
                BigDecimal.ONE,
                Money.of(500_000),
                BigDecimal.ZERO,
                true,
                BigDecimal.valueOf(19)
        ));
        factura.consolidate();

        // Agregar campos extra a la factura
        factura.addExtraField(defTexto, "OC-2027-0099");
        factura.addExtraField(defNumero, "1040");

        factura = repositorioFactura.saveAndFlush(factura);

        // Consultar factura desde la base de datos
        Invoice facturaGuardada = repositorioFactura.findById(factura.getId()).orElseThrow();
        assertEquals(2, facturaGuardada.getExtraFields().size());
        assertEquals(Optional.of("OC-2027-0099"), facturaGuardada.getExtraFieldValue(keyOc));
        assertEquals(Optional.of("1040"), facturaGuardada.getExtraFieldValue(keyCc));

        // Consultar directamente por el repositorio de campo extra
        List<InvoiceExtraField> camposPorFactura = repositorioCampoExtraFactura.findByInvoiceId(factura.getId());
        assertEquals(2, camposPorFactura.size());

        // Actualizar valor de campo extra
        factura.addExtraField(defTexto, "OC-2027-0099-MOD");
        factura = repositorioFactura.saveAndFlush(factura);
        assertEquals(Optional.of("OC-2027-0099-MOD"), factura.getExtraFieldValue(keyOc));
    }
}
