package com.sistemaspalacios.billing.infrastructure.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.security.Principal;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Seguridad de los canales STOMP. Reutiliza el mismo JWT del API REST (no hay autenticación
 * paralela) y no confía en que el cliente "no conozca" un topic:
 * <ul>
 *   <li>CONNECT: con autenticación activa exige {@code Authorization: Bearer <jwt>} en las cabeceras STOMP.</li>
 *   <li>/topic/admin/**: permisos INVOICES_VIEW o PAYMENTS_VIEW.</li>
 *   <li>/topic/invoices/{nit}: esos mismos permisos o ROL_PASARELA; si el token trae el claim
 *       {@code nit}, únicamente ese NIT.</li>
 *   <li>/topic/security: cualquier usuario autenticado (solo avisa que cambió la configuración de
 *       accesos; el contenido real se consulta en /api/v1/auth/me).</li>
 *   <li>Cualquier otro destino o envío de mensajes desde el cliente: rechazado.</li>
 * </ul>
 * Con autenticación desactivada (desarrollo local) se asume el mismo usuario local con todos los
 * permisos que usa el API REST en ese modo.
 */
public class StompSecurityInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompSecurityInterceptor.class);
    private static final Set<String> STAFF = Set.of("INVOICES_VIEW", "PAYMENTS_VIEW");
    private static final Set<String> GATEWAY = Set.of("ROL_PASARELA", "ROLE_PASARELA");

    private final boolean authEnabled;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter authenticationConverter;
    private final Supplier<? extends Collection<? extends GrantedAuthority>> localAuthorities;

    public StompSecurityInterceptor(boolean authEnabled, JwtDecoder jwtDecoder, JwtAuthenticationConverter authenticationConverter,
                                    Supplier<? extends Collection<? extends GrantedAuthority>> localAuthorities) {
        this.authEnabled = authEnabled;
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter = authenticationConverter;
        this.localAuthorities = localAuthorities;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> accessor.setUser(authenticate(accessor));
            case SUBSCRIBE -> authorizeSubscription(accessor.getUser(), accessor.getDestination());
            case SEND -> throw new MessageDeliveryException("El cliente no puede enviar mensajes por este canal");
            default -> { }
        }
        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor) {
        if (!authEnabled) {
            return new UsernamePasswordAuthenticationToken("admin-local", null, localAuthorities.get());
        }
        String header = Optional.ofNullable(accessor.getFirstNativeHeader("Authorization"))
                .orElse(accessor.getFirstNativeHeader("authorization"));
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new MessageDeliveryException("Conexión WebSocket sin token Bearer");
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(7).trim());
            return authenticationConverter.convert(jwt);
        } catch (JwtException ex) {
            log.warn("CONNECT WebSocket rechazado: token inválido ({})", ex.getMessage());
            throw new MessageDeliveryException("Token inválido o expirado");
        }
    }

    void authorizeSubscription(Principal user, String destination) {
        if (!(user instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            throw new MessageDeliveryException("Suscripción sin autenticación");
        }
        Set<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean staff = roles.stream().anyMatch(STAFF::contains);

        if (RealtimeTopics.SECURITY.equals(destination)) {
            return;
        }
        if (RealtimeTopics.ADMIN_INVOICES.equals(destination)) {
            if (staff) return;
            throw denied(authentication, destination);
        }
        Optional<String> nit = RealtimeTopics.nitFromDestination(destination);
        if (nit.isPresent()) {
            if (staff) return;
            if (roles.stream().anyMatch(GATEWAY::contains)) {
                String scopedNit = scopedNit(authentication);
                if (scopedNit == null || RealtimeTopics.normalizeNit(scopedNit).equalsIgnoreCase(nit.get())) return;
            }
            throw denied(authentication, destination);
        }
        throw denied(authentication, destination);
    }

    /** NIT al que está limitado el token de la pasarela, si el emisor lo incluye como claim. */
    private static String scopedNit(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            Object claim = jwt.getClaims().get("nit");
            return claim != null ? claim.toString() : null;
        }
        return null;
    }

    private static MessageDeliveryException denied(Authentication authentication, String destination) {
        log.warn("SUBSCRIBE WebSocket denegado a '{}' para el destino {}", authentication.getName(), destination);
        return new MessageDeliveryException("No autorizado para suscribirse a " + destination);
    }
}
