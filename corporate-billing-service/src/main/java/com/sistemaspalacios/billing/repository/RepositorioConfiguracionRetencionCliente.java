package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.ClientRetentionConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ClientRetentionConfiguration (configuracion_retencion_cliente).
 */
public interface RepositorioConfiguracionRetencionCliente extends JpaRepository<ClientRetentionConfiguration, UUID> {

    Optional<ClientRetentionConfiguration> findByClientId(UUID clientId);
}
