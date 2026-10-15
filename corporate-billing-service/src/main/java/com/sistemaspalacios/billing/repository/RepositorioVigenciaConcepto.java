package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for TaxConceptValidity (vigencia_concepto).
 */
@Repository
public interface RepositorioVigenciaConcepto extends JpaRepository<TaxConceptValidity, UUID> {

    List<TaxConceptValidity> findByStatus(RecordStatus status);

    @Query("SELECT v FROM TaxConceptValidity v WHERE v.status = 'ACTIVO' AND v.validFrom <= :date AND (v.validTo IS NULL OR v.validTo >= :date)")
    List<TaxConceptValidity> findActiveAtDate(@Param("date") LocalDate date);

    @Query("SELECT v FROM TaxConceptValidity v WHERE v.conceptCode = :conceptCode AND v.status = 'ACTIVO' AND v.validFrom <= :date AND (v.validTo IS NULL OR v.validTo >= :date)")
    List<TaxConceptValidity> findByConceptCodeAndDate(@Param("conceptCode") TaxConceptCode conceptCode, @Param("date") LocalDate date);
}
