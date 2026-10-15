package com.sistemaspalacios.billing.service.access;

import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.repository.RepositorioRol;
import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Crea el administrador inicial (rol SUPER_ADMIN) a partir de INITIAL_ADMIN_USERNAME e
 * INITIAL_ADMIN_EMAIL. No se duplica: si el usuario o el correo ya existen, no hace nada. No hay
 * contraseña: el administrador inicia sesión con su cuenta del microservicio de autenticación.
 */
@Component
public class InitialAdminProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminProvisioner.class);

    private final RepositorioUsuario users;
    private final RepositorioRol roles;
    private final String username;
    private final String email;
    private final String password;

    public InitialAdminProvisioner(RepositorioUsuario users, RepositorioRol roles,
                                   @Value("${access.initial-admin.username:}") String username,
                                   @Value("${access.initial-admin.email:}") String email,
                                   @Value("${access.initial-admin.password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password != null && !password.isBlank()) {
            log.warn("INITIAL_ADMIN_PASSWORD se ignora: las contraseñas las gestiona el microservicio de autenticación");
        }
        if (username == null || username.isBlank() || email == null || email.isBlank()) {
            log.info("Administrador inicial no configurado (INITIAL_ADMIN_USERNAME / INITIAL_ADMIN_EMAIL)");
            return;
        }
        String normalizedUser = UserAccount.normalizeUsername(username);
        String normalizedEmail = UserAccount.normalizeEmail(email);
        if (users.existsUsername(normalizedUser) || users.existsEmail(normalizedEmail, null)) {
            log.info("Administrador inicial '{}' ya existe; no se crea de nuevo", normalizedUser);
            return;
        }
        AccessRole superAdmin = roles.findAllByOrderByNameAsc().stream()
                .filter(r -> AccessRole.SUPER_ADMIN.equals(r.getName())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No existe el rol SUPER_ADMIN (migración V21)"));
        UserAccount admin = new UserAccount(normalizedUser, normalizedEmail, "Administrador", "Inicial", "SISTEMA");
        admin.replaceRoles(Set.of(superAdmin), "SISTEMA");
        users.save(admin);
        log.info("=== ADMINISTRADOR INICIAL CREADO === usuario={} correo={} rol=SUPER_ADMIN", normalizedUser, normalizedEmail);
    }
}
