package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.BillingIncident;
import com.sistemaspalacios.billing.domain.model.BillingIncidentStatus;
import com.sistemaspalacios.billing.domain.model.BillingIncidentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepositorioIncidenciaFacturacion extends JpaRepository<BillingIncident, UUID> {

    List<BillingIncident> findByClientId(UUID clientId);

    List<BillingIncident> findByPeriod(String period);

    List<BillingIncident> findByStatus(BillingIncidentStatus status);

    Optional<BillingIncident> findByClientIdAndPeriodAndIncidentType(UUID clientId, String period, BillingIncidentType incidentType);

    Optional<BillingIncident> findByNitAndPeriodAndIncidentType(String nit, String period, BillingIncidentType incidentType);

    List<BillingIncident> findByClientIdAndPeriod(UUID clientId, String period);

    List<BillingIncident> findByPeriodAndStatus(String period, BillingIncidentStatus status);
}
