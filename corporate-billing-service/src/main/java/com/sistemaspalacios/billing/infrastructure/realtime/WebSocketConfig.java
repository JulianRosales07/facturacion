package com.sistemaspalacios.billing.infrastructure.realtime;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.sistemaspalacios.billing.service.access.CurrentAccessService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WebSocket + STOMP sobre el mismo servidor HTTP (sin SockJS ni puerto adicional): {@code /ws}.
 * Los orígenes permitidos son los mismos de CORS ({@code cors.allowed-origins}), nunca "*".
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    /** Latidos servidor ↔ cliente para detectar conexiones muertas. */
    static final long HEARTBEAT_MS = 10_000;

    private final String[] allowedOrigins;
    private final boolean authEnabled;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<JwtAuthenticationConverter> authenticationConverter;
    private final ObjectProvider<CurrentAccessService> currentAccess;
    // Scheduler propio (no es un bean) para no reemplazar el que usan las tareas @Scheduled.
    private final ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();

    public WebSocketConfig(
            @Value("${cors.allowed-origins:http://localhost:4200,http://localhost:4300}") String allowedOrigins,
            @Value("${auth.enabled:true}") boolean authEnabled,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<JwtAuthenticationConverter> authenticationConverter,
            ObjectProvider<CurrentAccessService> currentAccess) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        this.authEnabled = authEnabled;
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter = authenticationConverter;
        this.currentAccess = currentAccess;
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
        heartbeatScheduler.initialize();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompSecurityInterceptor(authEnabled, jwtDecoder.getIfAvailable(),
                authenticationConverter.getIfAvailable(), this::localDevelopmentAuthorities));
    }

    /** Modo local (auth.enabled=false): los mismos permisos que el API REST, más el rol de la pasarela. */
    private List<GrantedAuthority> localDevelopmentAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        currentAccess.getObject().localDevelopmentUser().permissions()
                .forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        authorities.add(new SimpleGrantedAuthority("ROL_PASARELA"));
        return authorities;
    }

    @Override
    public void destroy() {
        heartbeatScheduler.shutdown();
    }
}
