package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.CarteraSnapshotLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository para las filas del corte de cartera (cartera_snapshot_linea).
 */
@Repository
public interface RepositorioCarteraSnapshotLinea extends JpaRepository<CarteraSnapshotLine, UUID> {

    List<CarteraSnapshotLine> findBySnapshotId(UUID snapshotId);

    List<CarteraSnapshotLine> findByInvoiceId(UUID invoiceId);

    /** Filas que Siigo reportó y el microservicio no pudo asociar a ninguna factura. */
    @Query("""
            SELECT l FROM CarteraSnapshotLine l
            WHERE l.snapshotId = :snapshotId AND l.lineStatus <> 'APLICADA'
            ORDER BY l.rowNumber ASC
            """)
    List<CarteraSnapshotLine> findUnresolvedBySnapshot(@Param("snapshotId") UUID snapshotId);
}
