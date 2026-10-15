package com.sistemaspalacios.billing.infrastructure.security;

import com.sistemaspalacios.billing.service.access.CurrentAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for Corporate Billing Service acting as an OAuth2 JWT Resource Server.
 * Sprint 8 (RF-31, RF-32, RF-35, RNF-23, RNF-24, RNF-25).
 * La identidad la emite el microservicio de autenticación; la autorización se basa en permisos
 * cargados de la base de datos en cada petición (AccessAuthoritiesConverter + @PreAuthorize).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Value("${auth.jwt.issuer-uri:${auth.issuer-uri:https://qa.sistemaspalacios.com}}")
    private String issuerUri;

    @Value("${auth.jwt.jwk-set-uri:${auth.jwk-set-uri:https://qa.sistemaspalacios.com/oauth2/jwks}}")
    private String jwkSetUri;

    @Value("${auth.jwt.audience:${auth.audience:corporate-billing-service}}")
    private String audience;

    @Value("${cors.allowed-origins:http://localhost:4200,http://localhost:4300,http://localhost:3000,http://localhost:5173,https://panel.sistemaspalacios.com,https://pasarela.sistemaspalacios.com}")
    private String allowedOrigins;

    @Value("${auth.enabled:false}")
    private boolean authEnabled;

    private final CurrentAccessService currentAccess;

    public SecurityConfig(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CurrentAccessService currentAccess
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.currentAccess = currentAccess;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, @org.springframework.beans.factory.annotation.Autowired(required = false) JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!authEnabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore((request, response, chain) -> {
                    if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() == null) {
                        // Desarrollo local: administrador con todos los permisos activos del catálogo,
                        // más los roles técnicos para poder probar la pasarela.
                        var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
                        var localUser = currentAccess.localDevelopmentUser();
                        localUser.permissions().forEach(code -> authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(code)));
                        localUser.roles().forEach(role -> authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
                        AccessAuthoritiesConverter.TECHNICAL_ROLES.forEach(role -> authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(role)));
                        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                CurrentAccessService.LOCAL_USER, null, authorities);
                        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                    chain.doFilter(request, response);
                }, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        http
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (HU-37, RF-35)
                        .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
                        // Handshake WebSocket: el navegador no puede enviar Authorization en el upgrade.
                        // El JWT se exige en el CONNECT de STOMP (StompSecurityInterceptor).
                        .requestMatchers("/ws").permitAll()
                        // Resto de Actuator: procesos técnicos o el rol SUPER_ADMIN
                        .requestMatchers("/actuator/**").hasAnyAuthority("ROL_SISTEMA", "ROLE_SISTEMA", "ROLE_SUPER_ADMIN")
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new AccessAuthoritiesConverter(currentAccess));
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder() {
        if (!authEnabled) {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("dev-user")
                    .claim("preferred_username", "admin-local")
                    .claim("name", "Julián Palacios")
                    .claim("email", "julian@sistemaspalacios.com")
                    .issuedAt(java.time.Instant.now())
                    .expiresAt(java.time.Instant.now().plusSeconds(86400))
                    .build();
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(this.jwkSetUri).build();

        OAuth2TokenValidator<Jwt> defaultTimestampValidator = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> audienceAndIssuerValidator = new AudienceAndIssuerValidator(this.audience, this.issuerUri);
        OAuth2TokenValidator<Jwt> combinedValidator = new DelegatingOAuth2TokenValidator<>(
                defaultTimestampValidator,
                audienceAndIssuerValidator
        );

        decoder.setJwtValidator(combinedValidator);
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // X-Client-Channel y X-Selection-Session: canal y sesión de la auditoría de retenciones.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin",
                "X-Client-Channel", "X-Selection-Session"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
