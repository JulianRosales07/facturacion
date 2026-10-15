package com.sistemaspalacios.billing.web.controller.admin;

import com.sistemaspalacios.billing.service.access.AccessDtos.PageResponse;
import com.sistemaspalacios.billing.service.access.AccessDtos.RoleAssignmentRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.StatusRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserFilter;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserRequest;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserResponse;
import com.sistemaspalacios.billing.service.access.AccessDtos.UserUpdateRequest;
import com.sistemaspalacios.billing.service.access.UserAdministrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Administración de usuarios. Cada operación exige su permiso; el frontend solo adapta la interfaz. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserAdministrationService service;

    public AdminUserController(UserAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USERS_VIEW')")
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(new UserFilter(username, name, email, active, role), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_VIEW')")
    public UserResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_CREATE')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_UPDATE')")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UserUpdateRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USERS_UPDATE')")
    public UserResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('USERS_UPDATE')")
    public UserResponse replaceRoles(@PathVariable UUID id, @Valid @RequestBody RoleAssignmentRequest request) {
        return service.replaceRoles(id, request.roleIds());
    }

    /** Eliminación lógica (se conserva para auditoría). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
