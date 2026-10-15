package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.InstallationFee;
import com.sistemaspalacios.billing.domain.model.InstallationFeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for InstallationFee (cuota_instalacion).
 */
@Repository
public interface RepositorioCuotaInstalacion extends JpaRepository<InstallationFee, UUID> {

    List<InstallationFee> findBySiteId(UUID siteId);

    List<InstallationFee> findBySiteIdAndStatus(UUID siteId, InstallationFeeStatus status);

    Optional<InstallationFee> findFirstBySiteIdAndStatusIn(UUID siteId, List<InstallationFeeStatus> statuses);
}
