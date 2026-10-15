package com.sistemaspalacios.billing.infrastructure.realtime;

import com.sistemaspalacios.billing.infrastructure.security.JwtRoleConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reglas de acceso a los canales WebSocket (mismo JWT que el API REST). */
class StompSecurityInterceptorTest {

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claims(c -> c.putAll(claims)).build();
    }

    private static StompSecurityInterceptor interceptor(Jwt tokenToReturn) {
        JwtDecoder decoder = token -> {
            if (tokenToReturn == null) throw new BadJwtException("invalid");
            return tokenToReturn;
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRoleConverter());
        return new StompSecurityInterceptor(true, decoder, converter, List::of);
    }

    private static Message<byte[]> frame(StompCommand command, String destination, String authorization, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) accessor.setDestination(destination);
        if (authorization != null) accessor.addNativeHeader("Authorization", authorization);
        if (user != null) accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Authentication user(String... roles) {
        return new UsernamePasswordAuthenticationToken("u", null, java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    @DisplayName("CONNECT sin token o con token inválido se rechaza; con token válido queda autenticado")
    void connectRequiresValidBearerToken() {
        var noToken = interceptor(null);
        assertThrows(MessageDeliveryException.class, () -> noToken.preSend(frame(StompCommand.CONNECT, null, null, null), null));
        assertThrows(MessageDeliveryException.class, () -> noToken.preSend(frame(StompCommand.CONNECT, null, "Bearer bad", null), null));

        var valid = interceptor(jwt(Map.of("roles", List.of("ROL_ANALISTA"))));
        Message<?> accepted = valid.preSend(frame(StompCommand.CONNECT, null, "Bearer good", null), null);
        Principal principal = StompHeaderAccessor.wrap(accepted).getUser();
        assertNotNull(principal);
    }

    @Test
    @DisplayName("Canal administrativo: solo con permiso de ver facturas o pagos")
    void adminTopicOnlyForStaff() {
        var security = interceptor(null);
        assertDoesNotThrow(() -> security.authorizeSubscription(user("INVOICES_VIEW"), RealtimeTopics.ADMIN_INVOICES));
        assertDoesNotThrow(() -> security.authorizeSubscription(user("PAYMENTS_VIEW"), RealtimeTopics.ADMIN_INVOICES));
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(user("USERS_VIEW"), RealtimeTopics.ADMIN_INVOICES));
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(user("ROL_ADMINISTRADOR"), RealtimeTopics.ADMIN_INVOICES),
                "los roles de personas del token ya no otorgan acceso");
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(user("ROL_PASARELA"), RealtimeTopics.ADMIN_INVOICES));
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(null, RealtimeTopics.ADMIN_INVOICES));
    }

    @Test
    @DisplayName("Canal del NIT: la pasarela con claim nit solo puede suscribirse a su propio NIT")
    void gatewayLimitedToItsNit() throws Exception {
        var security = interceptor(null);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRoleConverter());
        Authentication scoped = converter.convert(jwt(Map.of("roles", List.of("ROL_PASARELA"), "nit", "800092198")));

        assertDoesNotThrow(() -> security.authorizeSubscription(scoped, "/topic/invoices/800092198"));
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(scoped, "/topic/invoices/900123456"));
        assertDoesNotThrow(() -> security.authorizeSubscription(user("ROL_PASARELA"), "/topic/invoices/900123456"),
                "sin claim nit, la pasarela consulta cualquier NIT igual que en el API REST");
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(user("ROL_OTRO"), "/topic/invoices/900123456"));
    }

    @Test
    @DisplayName("Canal de seguridad: cualquier usuario autenticado, nunca anónimo")
    void securityTopicForAnyAuthenticatedUser() {
        var security = interceptor(null);
        assertDoesNotThrow(() -> security.authorizeSubscription(user("USERS_VIEW"), RealtimeTopics.SECURITY));
        assertDoesNotThrow(() -> security.authorizeSubscription(user(), RealtimeTopics.SECURITY));
        assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(null, RealtimeTopics.SECURITY));
    }

    @Test
    @DisplayName("Destinos desconocidos, comodines y envíos desde el cliente se rechazan")
    void everythingElseIsDenied() {
        var security = interceptor(null);
        Authentication admin = user("INVOICES_VIEW", "PAYMENTS_VIEW", "USERS_VIEW");
        for (String destination : new String[]{"/topic/all", "/topic/invoices/**", "/topic/invoices/../admin/invoices", "/queue/x", "/app/x"}) {
            assertThrows(MessageDeliveryException.class, () -> security.authorizeSubscription(admin, destination), destination);
        }
        assertThrows(MessageDeliveryException.class,
                () -> security.preSend(frame(StompCommand.SEND, "/app/anything", null, admin), null));
    }
}
