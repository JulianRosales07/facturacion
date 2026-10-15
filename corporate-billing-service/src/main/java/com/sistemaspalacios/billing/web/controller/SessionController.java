package com.sistemaspalacios.billing.web.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;

@RestController
public class SessionController {
    @GetMapping("/api/v1/sesion")
    @PreAuthorize("isAuthenticated()")
    public SessionInfo session(org.springframework.security.core.Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            var jwt = jwtAuth.getToken();
            return new SessionInfo(jwt.getSubject(), jwt.getClaimAsString("preferred_username"),
                    jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), jwt.getExpiresAt(),
                    authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList());
        }
        return new SessionInfo(
                authentication != null ? authentication.getName() : "admin-local",
                "admin-local",
                "Julián Palacios",
                "julian@sistemaspalacios.com",
                Instant.now().plusSeconds(86400),
                authentication != null ? authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList() : List.of("ROL_ADMINISTRADOR")
        );
    }
    public record SessionInfo(String subject, String username, String name, String email, Instant expiresAt, List<String> roles) {}
}
