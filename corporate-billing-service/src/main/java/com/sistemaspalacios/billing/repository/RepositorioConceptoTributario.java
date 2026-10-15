package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.TaxConcept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA Repository for TaxConcept (concepto_tributario).
 */
@Repository
public interface RepositorioConceptoTributario extends JpaRepository<TaxConcept, Short> {

    Optional<TaxConcept> findByCode(String code);
}
