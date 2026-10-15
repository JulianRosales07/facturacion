package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.*;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.service.dto.OfficialInvoiceData;
import com.sistemaspalacios.billing.service.siigo.OfficialInvoiceReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class OfficialInvoiceImportIntegrationTest {
    @Autowired OfficialInvoiceImportService importer;
    @Autowired OfficialInvoiceReader reader;
    @Autowired PaymentService payments;
    @Autowired IncomingInvoiceQueryService query;
    @Autowired IncomingFileIntakeService intake;
    @Autowired com.sistemaspalacios.billing.repository.IncomingFileHistoryRepository history;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;


    @Test
    void importsMultipleDocumentsSameMonthAndIsIdempotent() {
        String nit = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
        String number = Long.toString(Math.abs(new Random().nextLong() % 1000000000));
        var first = data(nit, number);
        Invoice a = importer.importDocument(first);
        Invoice b = importer.importDocument(data(nit, number + "1"));
        assertNotEquals(a.getId(), b.getId());
        assertEquals(a.getId(), importer.importDocument(first).getId());
        assertEquals(2, payments.getClientPortfolio(nit).totalPendingInvoices());
        assertEquals(Money.of("119.01"), a.getTotalAmount());
        assertThrows(IllegalStateException.class, a::assertCanBeRegenerated);
        assertThrows(IllegalStateException.class, () -> a.voidInvoice("No modificar factura oficial"));
        var changed = new OfficialInvoiceData(first.nit(), first.businessName(), first.billingType(), first.invoiceNumber(),
                first.issueDate(), first.dueDate(), null, first.total(),
                List.of(new OfficialInvoiceData.Item("SERV-2", "Detalle alterado", new BigDecimal("3"), Money.of("100.01"), Money.of("19"))));
        assertThrows(IllegalArgumentException.class, () -> importer.importDocument(changed));
    }

    @Test
    void readsActualSiigoMovementAndRejectsNonInvoiceWorkbooks() throws Exception {
        Path path = Path.of("Docs/archivosplanos/movimientocontableFACSEP.xlsx");
        try (var stream = Files.newInputStream(path)) {
            var docs = reader.read(path.getFileName().toString(), stream);
            assertEquals(1209, docs.size());
            for (var document : docs) {
                assertNotNull(document.nit());
                assertEquals(document.total(), document.items().stream().map(i -> i.subtotal().plus(i.iva())).reduce(Money.ZERO, Money::plus));
            }
            var invoice = docs.stream().filter(d -> d.invoiceNumber().equals("51753")).findFirst().orElseThrow();
            assertEquals("901114119", invoice.nit());
            assertEquals(Money.of(238000), invoice.total());
            assertEquals(Money.of(150000), invoice.items().getFirst().subtotal());
            assertEquals(Money.of(28500), invoice.items().getFirst().iva());
        }
        Path balances = Path.of("Docs/archivosplanos/saldos_de_cartera.xlsx");
        try (var stream = Files.newInputStream(balances)) {
            var docs = reader.read("saldos_de_cartera.xlsx", stream);
            assertFalse(docs.isEmpty());
            assertTrue(docs.stream().anyMatch(d -> d.invoiceNumber().equals("51993")));
        }
    }

    @Test
    void filtersBeforePaginationAcrossMoreThanFiftyInvoices() {
        String nit = "PAGE-" + UUID.randomUUID().toString().substring(0, 8);
        String prefix = Long.toString(Math.abs(new Random().nextLong() % 100000000));
        for (int i=0;i<54;i++) {
            var original = data(nit, prefix + String.format("%03d", i));
            importer.importDocument(new OfficialInvoiceData(original.nit(), original.businessName(), i<52?BillingType.F1:BillingType.F20,
                    original.invoiceNumber(), original.issueDate(), original.dueDate(), null, original.total(), original.items()));
        }
        var first = query.search(nit,null,null,null,null,null,null,0,25);
        var last = query.search(nit,null,null,null,null,null,null,2,25);
        assertEquals(54, first.totalElements()); assertEquals(3,first.totalPages()); assertEquals(4,last.items().size());
        var firstIds=first.items().stream().map(IncomingInvoiceQueryService.InvoiceRow::id).toList();
        assertTrue(last.items().stream().noneMatch(i->firstIds.contains(i.id())));
        assertEquals(2,query.search(nit,null,BillingType.F20,null,null,null,null,0,25).totalElements());
        assertEquals(0,query.search(nit,null,null,"2026-07",null,null,null,0,25).totalElements());
        assertEquals(54,query.search(nit,null,null,null,null,null,false,0,25).totalElements());
    }

    @Test
    void rejectionIsAuditedWithoutPersistingAnInvalidInvoice() {
        String filename="invalid-"+UUID.randomUUID()+".json";
        try {
            assertThrows(IllegalArgumentException.class,()->intake.receive(filename,"invalid JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8),"MANUAL"));
            var entries=history.findAll().stream().filter(h->h.getFilename().equals(filename)).toList();
            assertEquals(1,entries.size());assertEquals("RECHAZADO",entries.getFirst().getStatus());
            assertEquals(0,entries.getFirst().getCreated());assertNotNull(entries.getFirst().getSha256());
        } finally {
            var cleanup=new org.springframework.transaction.support.TransactionTemplate(transactions);
            cleanup.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            cleanup.executeWithoutResult(status->history.findAll().stream().filter(h->h.getFilename().equals(filename)).forEach(history::delete));
        }
    }

    private OfficialInvoiceData data(String nit, String number) {
        return new OfficialInvoiceData(nit, "Cliente de prueba", BillingType.F1, number,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), null, Money.of("119.01"),
                List.of(new OfficialInvoiceData.Item("SERV-1", "Servicio", new BigDecimal("3"), Money.of("100.01"), Money.of("19"))));
    }
}
