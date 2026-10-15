package com.sistemaspalacios.billing.infrastructure.security;

import com.sistemaspalacios.billing.service.access.CurrentAccessService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Autoridades de cada petición autenticada con JWT:
 * <ul>
 *   <li>Personas: los permisos efectivos de su usuario registrado (unión de sus roles activos),
 *       más {@code ROLE_<rol>}. Se leen de la base de datos, no del token: los roles de personas que
 *       traiga el token no otorgan acceso.</li>
 *   <li>Clientes técnicos (pasarela de pagos y procesos automáticos): conservan ROL_PASARELA y
 *       ROL_SISTEMA del token, porque no son usuarios del panel.</li>
 * </ul>
 */
public class AccessAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final Set<String> TECHNICAL_ROLES = Set.of("ROL_PASARELA", "ROLE_PASARELA", "ROL_SISTEMA", "ROLE_SISTEMA");

    private final JwtRoleConverter tokenRoles = new JwtRoleConverter();
    private final CurrentAccessService access;

    public AccessAuthoritiesConverter(CurrentAccessService access) {
        this.access = access;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (GrantedAuthority authority : tokenRoles.convert(jwt)) {
            String name = authority.getAuthority();
            if (TECHNICAL_ROLES.contains(name) || name.startsWith("SCOPE_")) {
                authorities.add(authority);
            }
        }
        access.resolve(login(jwt), jwt.getClaimAsString("email")).ifPresent(user -> {
            user.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
            user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        });
        return authorities;
    }

    /** Identificador de inicio de sesión del token: preferred_username, username o sub. */
    public static String login(Jwt jwt) {
        for (String claim : new String[]{"preferred_username", "username"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) return value;
        }
        return jwt.getSubject();
    }
}
