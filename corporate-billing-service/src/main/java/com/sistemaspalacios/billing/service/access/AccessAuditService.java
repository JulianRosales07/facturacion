package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.model.AccessAuditEntry;
import com.sistemaspalacios.billing.repository.RepositorioAuditoriaAcceso;
import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Deja constancia de cada cambio administrativo en la misma transacción que el cambio. */
@Service
public class AccessAuditService {

    private static final Logger log = LoggerFactory.getLogger(AccessAuditService.class);

    public static final String USER = "USUARIO";
    public static final String ROLE = "ROL";
    public static final String PERMISSION = "PERMISO";

    private final RepositorioAuditoriaAcceso audit;
    private final CurrentUserProvider currentUser;

    public AccessAuditService(RepositorioAuditoriaAcceso audit, CurrentUserProvider currentUser) {
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public String actor() {
        return currentUser.getCurrentUsername();
    }

    public void record(String action, String entity, UUID entityId, String detail) {
        String actor = actor();
        audit.save(new AccessAuditEntry(actor, action, entity, entityId, detail));
        log.info("=== AUDITORÍA ACCESOS === actor={} acción={} entidad={} id={} detalle={}", actor, action, entity, entityId, detail);
    }
}
