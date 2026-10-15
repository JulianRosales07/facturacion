package com.sistemaspalacios.billing.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    @DisplayName("Maps 'roles' claim array to ROL_* and ROLE_* authorities")
    void shouldMapRolesClaim() {
        Jwt jwt = createJwt(Map.of(
                "roles", List.of("ANALISTA", "ROL_ADMINISTRADOR")
        ));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        Set<String> authorityNames = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("ROL_ANALISTA", "ROLE_ANALISTA");
        assertThat(authorityNames).contains("ROL_ADMINISTRADOR", "ROLE_ADMINISTRADOR");
    }

    @Test
    @DisplayName("Maps Keycloak-style 'realm_access.roles' claim")
    void shouldMapRealmAccessRoles() {
        Jwt jwt = createJwt(Map.of(
                "realm_access", Map.of("roles", List.of("PASARELA"))
        ));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        Set<String> authorityNames = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("ROL_PASARELA", "ROLE_PASARELA");
    }

    @Test
    @DisplayName("Maps 'scope' space-delimited claim to SCOPE_* authorities")
    void shouldMapScopes() {
        Jwt jwt = createJwt(Map.of(
                "scope", "read write billing"
        ));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        Set<String> authorityNames = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("SCOPE_read", "SCOPE_write", "SCOPE_billing");
    }

    @Test
    @DisplayName("Maps 'authorities' and 'groups' claims")
    void shouldMapAuthoritiesAndGroups() {
        Jwt jwt = createJwt(Map.of(
                "authorities", List.of("SISTEMA"),
                "groups", List.of("FINANCE")
        ));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        Set<String> authorityNames = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("ROL_SISTEMA", "ROLE_SISTEMA");
        assertThat(authorityNames).contains("ROL_FINANCE", "ROLE_FINANCE");
    }

    private Jwt createJwt(Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                claims
        );
    }
}
