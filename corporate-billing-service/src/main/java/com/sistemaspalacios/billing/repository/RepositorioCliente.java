package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for CorporateClient (cliente_corporativo).
 */
@Repository
public interface RepositorioCliente extends JpaRepository<CorporateClient, UUID> {

    Optional<CorporateClient> findByNit(String nit);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT c FROM CorporateClient c WHERE c.nit = :nit")
    Optional<CorporateClient> findByNitForUpdate(@org.springframework.data.repository.query.Param("nit") String nit);

    List<CorporateClient> findByStatus(RecordStatus status);

    boolean existsByNit(String nit);
}
