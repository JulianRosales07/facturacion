package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.BillingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for BillingPeriod (periodo_facturacion).
 */
@Repository
public interface RepositorioPeriodo extends JpaRepository<BillingPeriod, UUID> {

    Optional<BillingPeriod> findByCode(String code);

    List<BillingPeriod> findByEstado(String estado);

    default List<BillingPeriod> findOpenPeriods() {
        return findByEstado("ABIERTO");
    }
}
