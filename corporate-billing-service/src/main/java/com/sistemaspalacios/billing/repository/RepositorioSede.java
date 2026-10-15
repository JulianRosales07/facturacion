package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import com.sistemaspalacios.billing.domain.model.RecordStatus;
import com.sistemaspalacios.billing.domain.model.SubscriptionSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for SubscriptionSite (sede_suscripcion).
 */
@Repository
public interface RepositorioSede extends JpaRepository<SubscriptionSite, UUID> {

    List<SubscriptionSite> findByClientId(UUID clientId);

    List<SubscriptionSite> findByClientIdAndStatus(UUID clientId, RecordStatus status);

    Optional<SubscriptionSite> findByExternalOrderCodeAndOrigin(String externalOrderCode, OrderOrigin origin);

    Optional<SubscriptionSite> findByNic(String nic);
}
