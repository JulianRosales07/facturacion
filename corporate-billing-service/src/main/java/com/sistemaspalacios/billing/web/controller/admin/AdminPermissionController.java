package com.sistemaspalacios.billing.web.controller.admin;

import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.PermissionResponse;
import com.sistemaspalacios.billing.service.access.PermissionAdministrationService;
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

/** Catálogo de permisos. El listado también lo usa quien asigna permisos a los roles. */
@RestController
@RequestMapping("/api/v1/admin/permissions")
public class AdminPermissionController {

    private final PermissionAdministrationService service;

    public AdminPermissionController(PermissionAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERMISSIONS_VIEW', 'PERMISSIONS_ASSIGN')")
    public List<PermissionResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSIONS_VIEW')")
    public PermissionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSIONS_MANAGE')")
    public ResponseEntity<PermissionResponse> create(@Valid @RequestBody PermissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSIONS_MANAGE')")
    public PermissionResponse update(@PathVariable UUID id, @Valid @RequestBody PermissionRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSIONS_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
