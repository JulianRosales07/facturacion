package com.sistemaspalacios.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("postgres")
class PostgresConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Verificar conexión real contra la base de datos PostgreSQL")
    void testRealDatabaseConnection() throws Exception {
        assertNotNull(dataSource, "El DataSource no debe ser nulo");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_database(), current_user, version()")) {

            assertTrue(rs.next(), "Debe retornar al menos una fila");
            String dbName = rs.getString(1);
            String user = rs.getString(2);
            String version = rs.getString(3);

            System.out.println("=================================================");
            System.out.println("CONEXIÓN EXITOSA CON POSTGRESQL:");
            System.out.println("Base de datos conectada : " + dbName);
            System.out.println("Usuario autenticado     : " + user);
            System.out.println("Versión de PostgreSQL   : " + version);
            System.out.println("=================================================");
        }
    }
}
