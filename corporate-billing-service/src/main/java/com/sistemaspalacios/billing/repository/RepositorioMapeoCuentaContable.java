package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.AccountingAccountMapping;
import com.sistemaspalacios.billing.domain.model.AccountingLineType;
import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for AccountingAccountMapping (mapeo_cuenta_contable).
 */
@Repository
public interface RepositorioMapeoCuentaContable extends JpaRepository<AccountingAccountMapping, UUID> {

    List<AccountingAccountMapping> findByStatus(RecordStatus status);

    List<AccountingAccountMapping> findByLineTypeAndStatus(AccountingLineType lineType, RecordStatus status);

    @Query("SELECT m FROM AccountingAccountMapping m WHERE m.lineType = :lineType " +
           "AND (m.billingType = :billingType OR m.billingType IS NULL) " +
           "AND m.status = 'ACTIVO' " +
           "ORDER BY m.billingType DESC NULLS LAST")
    List<AccountingAccountMapping> findMatchingMappings(
            @Param("lineType") AccountingLineType lineType,
            @Param("billingType") BillingType billingType
    );

    default Optional<AccountingAccountMapping> findFirstMatching(AccountingLineType lineType, BillingType billingType) {
        List<AccountingAccountMapping> matches = findMatchingMappings(lineType, billingType);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
