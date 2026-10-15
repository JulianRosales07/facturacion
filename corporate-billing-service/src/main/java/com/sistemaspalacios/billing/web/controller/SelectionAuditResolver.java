package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.SourceChannel;
import com.sistemaspalacios.billing.domain.vo.SelectionAudit;
import com.sistemaspalacios.billing.service.access.AccessDtos.CurrentUser;
import com.sistemaspalacios.billing.service.access.CurrentAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Contexto de auditoría de una petición que diligencia retenciones.
 * <ul>
 *   <li>Canal: lo declara cada frontend en {@code X-Client-Channel}; ADMIN_WEB solo se acepta de
 *       quien tiene permiso para registrar pagos. Sin canal válido: API (o MANUAL_PAYMENT en el
 *       registro manual).</li>
 *   <li>Actor: en el panel, el usuario autenticado (id, usuario, nombre). En la pasarela no hay
 *       usuario: se identifica al cliente por su NIT (lo agrega el servicio) y la sesión.</li>
 *   <li>Sesión ({@code X-Selection-Session}), IP y agente de usuario: trazabilidad, nunca identidad.</li>
 * </ul>
 * La hora no se toma de la petición: la fija el servidor al registrar.
 */
@Component
public class SelectionAuditResolver {

    public static final String CHANNEL_HEADER = "X-Client-Channel";
    public static final String SESSION_HEADER = "X-Selection-Session";
    private static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    /** Operación que diligencia retenciones. */
    public enum Operation { SIMULATE, CHECKOUT, REGISTER }

    private final CurrentAccessService access;

    public SelectionAuditResolver(CurrentAccessService access) {
        this.access = access;
    }

    public SelectionAudit resolve(HttpServletRequest request, Operation operation) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean staff = auth != null && auth.getAuthorities().stream().anyMatch(a -> "PAYMENTS_REGISTER".equals(a.getAuthority()));
        SourceChannel channel = channel(request.getHeader(CHANNEL_HEADER), staff, operation);
        String session = Optional.ofNullable(request.getHeader(SESSION_HEADER)).map(String::trim)
                .filter(s -> SESSION.matcher(s).matches()).orElse(null);
        String userAgent = request.getHeader("User-Agent");
        String ip = request.getRemoteAddr();

        if (channel == SourceChannel.PAYMENT_GATEWAY || !staff) {
            // Cliente en la pasarela: no se inventa un usuario (el NIT lo fija el servicio).
            return new SelectionAudit(channel, null, null, null, null, session, ip, userAgent);
        }
        CurrentUser user = currentUser(auth);
        return new SelectionAudit(channel, user != null ? user.id() : null, user != null ? user.username() : auth.getName(),
                user != null ? user.name() : null, null, session, ip, userAgent);
    }

    private static SourceChannel channel(String declared, boolean staff, Operation operation) {
        if ("PAYMENT_GATEWAY".equalsIgnoreCase(trim(declared))) return SourceChannel.PAYMENT_GATEWAY;
        if ("ADMIN_WEB".equalsIgnoreCase(trim(declared)) && staff) return SourceChannel.ADMIN_WEB;
        return operation == Operation.REGISTER ? SourceChannel.MANUAL_PAYMENT : SourceChannel.API;
    }

    private CurrentUser currentUser(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String login = Optional.ofNullable(jwt.getClaimAsString("preferred_username"))
                    .or(() -> Optional.ofNullable(jwt.getClaimAsString("username"))).orElse(jwt.getSubject());
            return access.resolve(login, jwt.getClaimAsString("email")).orElse(null);
        }
        if (auth != null && CurrentAccessService.LOCAL_USER.equals(auth.getName())) {
            return access.localDevelopmentUser();
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
