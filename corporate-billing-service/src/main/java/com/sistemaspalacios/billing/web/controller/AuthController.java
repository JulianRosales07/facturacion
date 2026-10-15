package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.access.AccessDtos.CurrentUser;
import com.sistemaspalacios.billing.service.access.CurrentAccessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usuario autenticado con sus roles y permisos efectivos, para que el frontend adapte la interfaz.
 * No otorga nada: el backend vuelve a evaluar los permisos en cada petición.
 */
@RestController
public class AuthController {

    private final CurrentAccessService access;

    public AuthController(CurrentAccessService access) {
        this.access = access;
    }

    @GetMapping("/api/v1/auth/me")
    @PreAuthorize("isAuthenticated()")
    public CurrentUser me(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String login = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("username"), jwt.getSubject());
            String email = jwt.getClaimAsString("email");
            return access.resolve(login, email)
                    .orElseGet(() -> access.unregistered(login, jwt.getClaimAsString("name"), email));
        }
        // Desarrollo local (auth.enabled=false).
        return access.localDevelopmentUser();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
