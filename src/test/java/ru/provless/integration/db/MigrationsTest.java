package ru.provless.integration.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Every migration applies on the Postgres version of the cluster (mesoreal-k8s/infra/base/postgres). */
@Testcontainers
class MigrationsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9-alpine3.23");

    @Test
    void allMigrationsApply() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().success).isTrue();

        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'chatwoot' AND table_name LIKE 'chat_%'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(5);
        }
    }
}
