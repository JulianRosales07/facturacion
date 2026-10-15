package com.sistemaspalacios.billing.infrastructure.security;

import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Spring Security-based implementation of CurrentUserProvider.
 * Extracts user identifier from JwtAuthenticationToken or Spring SecurityContext.
 */
@Component
public class SpringSecurityCurrentUserProvider implements CurrentUserProvider {

    private static final String DEFAULT_SYSTEM_USER = "SISTEMA";

    @Override
    public String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return DEFAULT_SYSTEM_USER;
        }

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (jwt.hasClaim("preferred_username") && jwt.getClaimAsString("preferred_username") != null) {
                return jwt.getClaimAsString("preferred_username");
            }
            if (jwt.hasClaim("email") && jwt.getClaimAsString("email") != null) {
                return jwt.getClaimAsString("email");
            }
            if (jwt.hasClaim("name") && jwt.getClaimAsString("name") != null) {
                return jwt.getClaimAsString("name");
            }
            if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
                return jwt.getSubject();
            }
            if (jwt.hasClaim("client_id") && jwt.getClaimAsString("client_id") != null) {
                return jwt.getClaimAsString("client_id");
            }
        }

        return auth.getName() != null && !auth.getName().isBlank() ? auth.getName() : DEFAULT_SYSTEM_USER;
    }

    @Override
    public Optional<String> getCurrentUserSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.ofNullable(jwtAuth.getToken().getSubject());
        }
        return Optional.ofNullable(auth != null ? auth.getName() : null);
    }
}
