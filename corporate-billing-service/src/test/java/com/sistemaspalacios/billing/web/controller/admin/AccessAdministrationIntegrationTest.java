package com.sistemaspalacios.billing.web.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.AccessPermission;
import com.sistemaspalacios.billing.domain.model.AccessRole;
import com.sistemaspalacios.billing.domain.model.UserAccount;
import com.sistemaspalacios.billing.repository.RepositorioAuditoriaAcceso;
import com.sistemaspalacios.billing.repository.RepositorioPermiso;
import com.sistemaspalacios.billing.repository.RepositorioRol;
import com.sistemaspalacios.billing.repository.RepositorioUsuario;
import com.sistemaspalacios.billing.service.ordenes.OrderSystemClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usuarios, roles y permisos con la seguridad real activa (auth.enabled=true). Las pruebas de
 * administración autentican con autoridades explícitas; las de extremo a extremo envían un Bearer
 * que el JwtDecoder simulado traduce a un token, de modo que los permisos se cargan de la base de
 * datos igual que en producción.
 */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "auth.enabled=true"})
@ActiveProfiles("postgres")
@Import(AccessAdministrationIntegrationTest.Config.class)
@Transactional
class AccessAdministrationIntegrationTest {

    private static final List<String> ADMIN_PERMISSIONS = List.of("USERS_VIEW", "USERS_CREATE", "USERS_UPDATE", "USERS_DELETE",
            "ROLES_VIEW", "ROLES_CREATE", "ROLES_UPDATE", "ROLES_DELETE", "PERMISSIONS_VIEW", "PERMISSIONS_MANAGE", "PERMISSIONS_ASSIGN");

    @TestConfiguration
    static class Config {
        static final JwtDecoder DECODER = Mockito.mock(JwtDecoder.class);

        // Nombre distinto de SecurityConfig#jwtDecoder para no ser reemplazado por el decodificador real.
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return DECODER;
        }

        @Bean
        @Primary
        OrderSystemClient orderSystemClient() {
            OrderSystemClient mock = Mockito.mock(OrderSystemClient.class);
            Mockito.when(mock.fetchSubscriptions(Mockito.anyString())).thenReturn(List.of());
            Mockito.when(mock.fetchUpdates(Mockito.anyString())).thenReturn(List.of());
            return mock;
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private RepositorioUsuario users;
    @Autowired private RepositorioRol roles;
    @Autowired private RepositorioPermiso permissions;
    @Autowired private RepositorioAuditoriaAcceso audit;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(Config.DECODER);
        // Administrador base: así la protección del último administrador no interfiere con el resto de casos.
        UserAccount base = new UserAccount("admin.base", "admin.base@empresa.com", "Admin", "Base", "prueba");
        base.replaceRoles(Set.of(roles.findById(roleId("SUPER_ADMIN")).orElseThrow()), "prueba");
        users.saveAndFlush(base);
    }

    // ---------- utilidades ----------

    /** Autenticación con autoridades explícitas (sin pasar por la base de datos). */
    private static RequestPostProcessor as(String username, String... authorities) {
        return jwt().jwt(j -> j.claim("preferred_username", username))
                .authorities(Arrays.stream(authorities).map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }

    private static RequestPostProcessor admin() {
        return as("admin.pruebas", ADMIN_PERMISSIONS.toArray(String[]::new));
    }

    /** Bearer real: el decoder simulado devuelve un JWT del usuario; los permisos salen de la base de datos. */
    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String username) {
        String token = "token-" + username;
        Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject("sub-" + username)
                .claim("preferred_username", username).claim("roles", List.of("ROL_ADMINISTRADOR"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        Mockito.when(Config.DECODER.decode(token)).thenReturn(jwt);
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private List<String> permissionIds(String... codes) {
        Set<String> wanted = Set.of(codes);
        List<String> ids = permissions.findAll().stream().filter(p -> wanted.contains(p.getCode()))
                .map(p -> p.getId().toString()).toList();
        assertThat(ids).hasSize(codes.length);
        return ids;
    }

    private UUID roleId(String name) {
        return roles.findAll().stream().filter(r -> r.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private JsonNode createRole(String name, String... codes) throws Exception {
        String response = mvc.perform(post("/api/v1/admin/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", name, "description", "Rol " + name, "permissionIds", permissionIds(codes)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode createUser(String username, String... roleIds) throws Exception {
        String response = mvc.perform(post("/api/v1/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "email", username + "@empresa.com", "firstName", "Nombre",
                                "lastName", "Apellido", "active", true, "roleIds", List.of(roleIds)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    // ---------- usuarios ----------

    @Nested
    @DisplayName("Usuarios")
    class Users {

        @Test
        @DisplayName("crear usuario: 201, sin contraseña ni hash en la respuesta, roles asignados y auditoría")
        void createsUser() throws Exception {
            UUID financiera = roleId("FINANCIERA");
            mvc.perform(post("/api/v1/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("username", "JPerez", "email", "JPerez@Empresa.com", "firstName", "Juan",
                                    "lastName", "Pérez", "active", true, "roleIds", List.of(financiera)))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.username", is("jperez")))
                    .andExpect(jsonPath("$.email", is("jperez@empresa.com")))
                    .andExpect(jsonPath("$.fullName", is("Juan Pérez")))
                    .andExpect(jsonPath("$.roles[0].name", is("FINANCIERA")))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.password").doesNotExist());

            UserAccount saved = users.findByUsername("jperez").orElseThrow();
            assertThat(audit.findByEntityAndEntityIdOrderByOccurredAtDesc("USUARIO", saved.getId()))
                    .extracting(e -> e.getAction() + "/" + e.getActor()).contains("USUARIO_CREADO/admin.pruebas");
        }

        @Test
        @DisplayName("la contraseña no se recibe: pertenece al microservicio de autenticación (400)")
        void passwordIsRejected() throws Exception {
            mvc.perform(post("/api/v1/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("username", "conclave", "email", "conclave@empresa.com", "firstName", "A",
                                    "lastName", "B", "password", "Temporal123!"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.mensaje", containsString("microservicio de autenticación")));
        }

        @Test
        @DisplayName("usuario y correo duplicados (sin distinguir mayúsculas): 409")
        void duplicates() throws Exception {
            createUser("duplicado");
            mvc.perform(post("/api/v1/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("username", "DUPLICADO", "email", "otro@empresa.com", "firstName", "A", "lastName", "B"))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje", containsString("ya existe")));
            mvc.perform(post("/api/v1/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("username", "otro", "email", "Duplicado@Empresa.com", "firstName", "A", "lastName", "B"))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje", containsString("ya está registrado")));
        }

        @Test
        @DisplayName("editar usuario, desactivar, asignar y quitar roles")
        void editStatusAndRoles() throws Exception {
            JsonNode created = createUser("editable");
            String id = created.get("id").asText();
            UUID soporte = roleId("SOPORTE");
            UUID financiera = roleId("FINANCIERA");

            mvc.perform(put("/api/v1/admin/users/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("email", "nuevo@empresa.com", "firstName", "Ana", "lastName", "Gómez",
                                    "version", created.get("version").asLong()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fullName", is("Ana Gómez"))).andExpect(jsonPath("$.email", is("nuevo@empresa.com")));

            mvc.perform(put("/api/v1/admin/users/" + id + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("roleIds", List.of(soporte, financiera)))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.roles", hasSize(2)));
            mvc.perform(put("/api/v1/admin/users/" + id + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("roleIds", List.of(financiera)))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.roles[*].name", containsInAnyOrder("FINANCIERA")));

            mvc.perform(patch("/api/v1/admin/users/" + id + "/status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("active", false))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.active", is(false)));

            mvc.perform(get("/api/v1/admin/users").with(admin()).param("active", "false").param("username", "editable"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].username", hasItem("editable")));
            mvc.perform(get("/api/v1/admin/users").with(admin()).param("role", "FINANCIERA").param("size", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].username", hasItem("editable")));
        }

        @Test
        @DisplayName("roles inexistentes: 404; edición con versión desactualizada: 409")
        void missingRoleAndStaleVersion() throws Exception {
            JsonNode created = createUser("concurrente");
            String id = created.get("id").asText();
            mvc.perform(put("/api/v1/admin/users/" + id + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("roleIds", List.of(UUID.randomUUID())))))
                    .andExpect(status().isNotFound());
            mvc.perform(put("/api/v1/admin/users/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("email", "x@empresa.com", "firstName", "A", "lastName", "B", "version", 999))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje", containsString("modificado por otro")));
        }

        @Test
        @DisplayName("eliminar es lógico: deja de listarse pero queda para auditoría")
        void softDelete() throws Exception {
            String id = createUser("eliminable").get("id").asText();
            mvc.perform(delete("/api/v1/admin/users/" + id).with(admin())).andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/admin/users/" + id).with(admin())).andExpect(status().isNotFound());
            assertThat(users.findById(UUID.fromString(id))).get().matches(UserAccount::isDeleted);
        }
    }

    // ---------- roles ----------

    @Nested
    @DisplayName("Roles")
    class Roles {

        @Test
        @DisplayName("crear, editar, asignar permisos y eliminar un rol")
        void lifecycle() throws Exception {
            JsonNode role = createRole("analista cartera", "INVOICES_VIEW", "PAYMENTS_VIEW");
            String id = role.get("id").asText();
            assertThat(role.get("name").asText()).isEqualTo("ANALISTA_CARTERA");
            assertThat(role.get("permissionCount").asInt()).isEqualTo(2);

            mvc.perform(put("/api/v1/admin/roles/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "ANALISTA_CARTERA", "description", "Gestión de recaudo", "active", true))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.description", is("Gestión de recaudo")))
                    .andExpect(jsonPath("$.permissionCount", is(2)));

            mvc.perform(put("/api/v1/admin/roles/" + id + "/permissions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("permissionIds", permissionIds("INVOICES_VIEW", "PAYMENTS_VIEW", "PAYMENTS_REGISTER")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[*].code", containsInAnyOrder("INVOICES_VIEW", "PAYMENTS_VIEW", "PAYMENTS_REGISTER")));

            mvc.perform(post("/api/v1/admin/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "Analista_Cartera"))))
                    .andExpect(status().isConflict());

            mvc.perform(delete("/api/v1/admin/roles/" + id).with(admin())).andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/admin/roles/" + id).with(admin())).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("los roles del sistema no se eliminan ni se renombran (409)")
        void systemRoleIsProtected() throws Exception {
            UUID superAdmin = roleId("SUPER_ADMIN");
            mvc.perform(delete("/api/v1/admin/roles/" + superAdmin).with(admin()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje", containsString("es del sistema")));
            mvc.perform(put("/api/v1/admin/roles/" + superAdmin).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "OTRO_NOMBRE"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("roles iniciales: SUPER_ADMIN todo, FINANCIERA todo menos administración, SOPORTE solo administración")
        void seededRoles() throws Exception {
            List<String> all = permissions.findAll().stream().filter(AccessPermission::isSystemPermission).map(AccessPermission::getCode).toList();
            AccessRole superAdmin = roles.findById(roleId("SUPER_ADMIN")).orElseThrow();
            AccessRole financiera = roles.findById(roleId("FINANCIERA")).orElseThrow();
            AccessRole soporte = roles.findById(roleId("SOPORTE")).orElseThrow();
            assertThat(superAdmin.getPermissions()).extracting(AccessPermission::getCode).containsAll(all);
            assertThat(financiera.getPermissions()).extracting(AccessPermission::getCode)
                    .contains("INVOICES_VIEW", "PAYMENTS_REGISTER", "TAX_CONCEPTS_MANAGE").doesNotContainAnyElementsOf(ADMIN_PERMISSIONS);
            assertThat(soporte.getPermissions()).extracting(AccessPermission::getCode).containsExactlyInAnyOrderElementsOf(ADMIN_PERMISSIONS);
        }
    }

    // ---------- permisos ----------

    @Nested
    @DisplayName("Permisos")
    class Permissions {

        @Test
        @DisplayName("crear, editar, evitar código duplicado y eliminar un permiso propio")
        void lifecycle() throws Exception {
            String response = mvc.perform(post("/api/v1/admin/permissions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("code", "reports_export", "name", "Exportar reportes", "module", "Reportes"))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.code", is("REPORTS_EXPORT")))
                    .andReturn().getResponse().getContentAsString();
            String id = json.readTree(response).get("id").asText();

            mvc.perform(post("/api/v1/admin/permissions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("code", "REPORTS_EXPORT", "name", "Otro", "module", "Reportes"))))
                    .andExpect(status().isConflict());

            mvc.perform(put("/api/v1/admin/permissions/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("code", "REPORTS_EXPORT", "name", "Exportar reportes a Excel", "module", "Reportes", "active", false))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("Exportar reportes a Excel")))
                    .andExpect(jsonPath("$.active", is(false)));

            mvc.perform(delete("/api/v1/admin/permissions/" + id).with(admin())).andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("un permiso del sistema conserva su código y no se elimina")
        void systemPermissionIsProtected() throws Exception {
            String id = permissionIds("INVOICES_VIEW").get(0);
            mvc.perform(put("/api/v1/admin/permissions/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("code", "INVOICES_READ", "name", "Ver facturas", "module", "Facturación"))))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete("/api/v1/admin/permissions/" + id).with(admin()))
                    .andExpect(status().isConflict());
        }
    }

    // ---------- seguridad ----------

    @Nested
    @DisplayName("Seguridad")
    class Security {

        @Test
        @DisplayName("sin USERS_VIEW → 403; con USERS_VIEW → 200; con USERS_VIEW sin USERS_CREATE: GET 200 y POST 403")
        void usersEndpointRequiresPermissions() throws Exception {
            mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/users").with(as("financiera", "INVOICES_VIEW", "PAYMENTS_VIEW")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo", is("ACCESO_DENEGADO")));
            mvc.perform(get("/api/v1/admin/users").with(as("consulta", "USERS_VIEW"))).andExpect(status().isOk());
            mvc.perform(post("/api/v1/admin/users").with(as("consulta", "USERS_VIEW")).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("username", "nuevo", "email", "nuevo@empresa.com", "firstName", "A", "lastName", "B"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("crear un rol con permisos exige además PERMISSIONS_ASSIGN")
        void creatingRoleWithPermissionsRequiresAssign() throws Exception {
            mvc.perform(post("/api/v1/admin/roles").with(as("soporte", "ROLES_CREATE")).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "SIN_ASIGNAR", "permissionIds", permissionIds("INVOICES_VIEW")))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/roles").with(as("soporte", "ROLES_CREATE")).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "SIN_PERMISOS"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("caso completo: analista1 con ANALISTA_CARTERA consulta facturas y pagos, registra pagos y no administra accesos")
        void analystEndToEnd() throws Exception {
            String roleId = createRole("ANALISTA_CARTERA", "INVOICES_VIEW", "PAYMENTS_VIEW", "PAYMENTS_REGISTER").get("id").asText();
            createUser("analista1", roleId);

            mvc.perform(bearer(get("/api/v1/auth/me"), "analista1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.registered", is(true)))
                    .andExpect(jsonPath("$.roles", containsInAnyOrder("ANALISTA_CARTERA")))
                    .andExpect(jsonPath("$.permissions", containsInAnyOrder("INVOICES_VIEW", "PAYMENTS_VIEW", "PAYMENTS_REGISTER")));

            // Puede: consultar facturas y pagos, y registrar pagos (pasa la autorización; el cuerpo vacío es 400).
            mvc.perform(bearer(get("/api/v1/facturas"), "analista1")).andExpect(status().isOk());
            mvc.perform(bearer(get("/api/v1/pagos/factura/" + UUID.randomUUID()), "analista1")).andExpect(status().isOk());
            mvc.perform(bearer(post("/api/v1/pagos"), "analista1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(not(403)));

            // No puede: crear usuarios, editar roles ni administrar permisos.
            mvc.perform(bearer(post("/api/v1/admin/users"), "analista1").contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("username", "x1", "email", "x1@empresa.com", "firstName", "A", "lastName", "B"))))
                    .andExpect(status().isForbidden());
            mvc.perform(bearer(put("/api/v1/admin/roles/" + roleId), "analista1").contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "ANALISTA_CARTERA"))))
                    .andExpect(status().isForbidden());
            mvc.perform(bearer(post("/api/v1/admin/permissions"), "analista1").contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("code", "X_Y", "name", "x", "module", "x"))))
                    .andExpect(status().isForbidden());
            // El rol ROL_ADMINISTRADOR que trae el token no otorga nada.
            mvc.perform(bearer(post("/api/v1/impuestos/vigencias"), "analista1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("permisos efectivos = unión sin duplicados de todos los roles; un cambio aplica en la siguiente petición")
        void effectivePermissionsAreUnionAndImmediate() throws Exception {
            String a = createRole("ROL_A", "INVOICES_VIEW", "PAYMENTS_VIEW").get("id").asText();
            String b = createRole("ROL_B", "REPORTS_VIEW", "PAYMENTS_REGISTER", "PAYMENTS_VIEW").get("id").asText();
            String userId = createUser("doble.rol", a, b).get("id").asText();

            mvc.perform(bearer(get("/api/v1/auth/me"), "doble.rol"))
                    .andExpect(jsonPath("$.permissions", containsInAnyOrder("INVOICES_VIEW", "PAYMENTS_VIEW", "REPORTS_VIEW", "PAYMENTS_REGISTER")));
            mvc.perform(bearer(get("/api/v1/facturas"), "doble.rol")).andExpect(status().isOk());

            mvc.perform(put("/api/v1/admin/users/" + userId + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("roleIds", List.of(b))))).andExpect(status().isOk());
            mvc.perform(bearer(get("/api/v1/facturas"), "doble.rol")).andExpect(status().isForbidden());

            mvc.perform(patch("/api/v1/admin/users/" + userId + "/status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("active", false)))).andExpect(status().isOk());
            mvc.perform(bearer(get("/api/v1/auth/me"), "doble.rol"))
                    .andExpect(jsonPath("$.registered", is(false))).andExpect(jsonPath("$.permissions", hasSize(0)));
        }

        @Test
        @DisplayName("identidad del SSO sin usuario registrado: autenticada pero sin permisos")
        void unregisteredIdentity() throws Exception {
            mvc.perform(bearer(get("/api/v1/auth/me"), "desconocido"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.registered", is(false)));
            mvc.perform(bearer(get("/api/v1/facturas"), "desconocido")).andExpect(status().isForbidden());
        }

        /**
         * Deja como único administrador a "unico.admin" (todo se revierte al terminar la prueba).
         * Cada caso hace una sola operación protegida: en producción el 409 revierte su transacción,
         * pero aquí todas las peticiones comparten la transacción de la prueba.
         */
        private String onlyAdministrator() throws Exception {
            users.findAll().forEach(u -> u.changeStatus(false, "prueba"));
            users.flush();
            return createUser("unico.admin", roleId("SUPER_ADMIN").toString()).get("id").asText();
        }

        @Test
        @DisplayName("último administrador: no se puede desactivar (409)")
        void lastAdministratorCannotBeDeactivated() throws Exception {
            String adminId = onlyAdministrator();
            mvc.perform(patch("/api/v1/admin/users/" + adminId + "/status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("active", false))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje", containsString("sin ningún usuario activo")));
        }

        @Test
        @DisplayName("último administrador: no se le puede quitar el rol (409)")
        void lastAdministratorKeepsRole() throws Exception {
            String adminId = onlyAdministrator();
            mvc.perform(put("/api/v1/admin/users/" + adminId + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("roleIds", List.of())))).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("último administrador: no se pueden vaciar los permisos de su rol (409)")
        void lastAdministratorRoleKeepsPermissions() throws Exception {
            onlyAdministrator();
            mvc.perform(put("/api/v1/admin/roles/" + roleId("SUPER_ADMIN") + "/permissions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("permissionIds", permissionIds("INVOICES_VIEW"))))).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("último administrador: no se puede eliminar (409)")
        void lastAdministratorCannotBeDeleted() throws Exception {
            String adminId = onlyAdministrator();
            mvc.perform(delete("/api/v1/admin/users/" + adminId).with(admin())).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("con un segundo administrador activo, el primero sí se puede desactivar")
        void secondAdministratorAllowsChange() throws Exception {
            String adminId = onlyAdministrator();
            String otherId = createUser("segundo").get("id").asText();
            mvc.perform(put("/api/v1/admin/users/" + otherId + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("roleIds", List.of(roleId("SUPER_ADMIN")))))).andExpect(status().isOk());
            mvc.perform(patch("/api/v1/admin/users/" + adminId + "/status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("active", false)))).andExpect(status().isOk());
        }
    }
}
