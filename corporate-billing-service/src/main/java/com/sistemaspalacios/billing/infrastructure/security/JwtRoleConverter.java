package com.sistemaspalacios.billing.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;

/**
 * Maps JWT claims (roles, realm_access, groups, authorities, scope)
 * to Spring Security GrantedAuthorities.
 * Supports canonical roles: ROL_ANALISTA, ROL_ADMINISTRADOR, ROL_PASARELA, ROL_SISTEMA.
 */
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> rawRoles = new HashSet<>();

        // 1. Direct "roles" claim (List<String> or comma-separated String)
        extractStrings(jwt.getClaims().get("roles"), rawRoles);

        // 2. "authorities" claim
        extractStrings(jwt.getClaims().get("authorities"), rawRoles);

        // 3. "groups" claim
        extractStrings(jwt.getClaims().get("groups"), rawRoles);

        // 4. Keycloak-style "realm_access.roles"
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            extractStrings(map.get("roles"), rawRoles);
        }

        // 5. "scope" or "scp" claims
        extractScopes(jwt.getClaims().get("scope"), rawRoles);
        extractScopes(jwt.getClaims().get("scp"), rawRoles);

        // Build GrantedAuthority set
        Set<GrantedAuthority> authorities = new HashSet<>();
        for (String raw : rawRoles) {
            String clean = raw.trim();
            if (clean.isBlank()) continue;

            String upper = clean.toUpperCase();
            String baseRole = upper;
            if (baseRole.startsWith("ROLE_")) {
                baseRole = baseRole.substring(5);
            } else if (baseRole.startsWith("ROL_")) {
                baseRole = baseRole.substring(4);
            }

            // Add both ROL_<ROLE> and ROLE_<ROLE> for maximum compatibility
            authorities.add(new SimpleGrantedAuthority("ROL_" + baseRole));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + baseRole));
            authorities.add(new SimpleGrantedAuthority(clean));
        }

        return authorities;
    }

    private void extractStrings(Object claimValue, Set<String> target) {
        if (claimValue instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null) {
                    target.add(item.toString());
                }
            }
        } else if (claimValue instanceof String str) {
            for (String part : str.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    target.add(part);
                }
            }
        }
    }

    private void extractScopes(Object claimValue, Set<String> target) {
        if (claimValue instanceof String str) {
            for (String scope : str.split("\\s+")) {
                if (!scope.isBlank()) {
                    target.add(scope);
                    target.add("SCOPE_" + scope);
                }
            }
        } else if (claimValue instanceof Collection<?> col) {
            for (Object scope : col) {
                if (scope != null) {
                    target.add(scope.toString());
                    target.add("SCOPE_" + scope.toString());
                }
            }
        }
    }
}
