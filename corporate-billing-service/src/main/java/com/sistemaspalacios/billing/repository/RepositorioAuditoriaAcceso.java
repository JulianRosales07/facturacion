package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.AccessAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Auditoría de la administración de accesos (auditoria_acceso). */
public interface RepositorioAuditoriaAcceso extends JpaRepository<AccessAuditEntry, UUID> {

    List<AccessAuditEntry> findByEntityAndEntityIdOrderByOccurredAtDesc(String entity, UUID entityId);
}
