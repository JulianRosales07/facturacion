package com.sistemaspalacios.billing.web.controller.admin;

import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionAssignmentRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleResponse;
import com.sistemaspalacios.billing.service.access.RoleAdministrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administración de roles. Crear o editar un rol enviando permisos exige además PERMISSIONS_ASSIGN.
 * El listado también lo usa quien crea o edita usuarios (para elegir sus roles).
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
public class AdminRoleController {

    private final RoleAdministrationService service;

    public AdminRoleController(RoleAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLES_VIEW', 'USERS_CREATE', 'USERS_UPDATE')")
    public List<RoleResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLES_VIEW')")
    public RoleResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLES_CREATE') and (#request.permissionIds() == null or #request.permissionIds().isEmpty() or hasAuthority('PERMISSIONS_ASSIGN'))")
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLES_UPDATE') and (#request.permissionIds() == null or hasAuthority('PERMISSIONS_ASSIGN'))")
    public RoleResponse update(@PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        return service.update(id, request);
    }

    /** Reemplaza de forma transaccional la asignación de permisos del rol. */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSIONS_ASSIGN')")
    public RoleResponse replacePermissions(@PathVariable UUID id, @Valid @RequestBody PermissionAssignmentRequest request) {
        return service.replacePermissions(id, request.permissionIds());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLES_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
